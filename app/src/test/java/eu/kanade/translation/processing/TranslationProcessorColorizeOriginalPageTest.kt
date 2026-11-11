package eu.kanade.translation.processing

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.FakeUniFile
import eu.kanade.translation.PageColorizeOutcome
import eu.kanade.translation.engines.coloring.PageColorizationEngine
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.LeaseAcquisition
import eu.kanade.translation.persistence.PageStageLeaseTable
import eu.kanade.translation.persistence.PageWriteOrigin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.TranslationReadingOrder
import java.io.File

/**
 * Outcome matrix for the reader-direct colorize primitive: every failure mode
 * must produce its typed outcome (never a lying COMPLETED) and the page stage
 * lease must always be released. These outcomes previously collapsed into a
 * silent "does nothing" auto-colorize experience.
 */
class TranslationProcessorColorizeOriginalPageTest {

    @TempDir
    lateinit var backing: File

    private val context: Context = mockk(relaxed = true)

    private class FakeEngine : PageColorizationEngine {
        override fun isAvailable(): Boolean = true
        override fun currentStamp(): String? = "test-stamp"
        override fun initializationError(): String? = null
        override fun colorize(source: Bitmap, seed: Long): Bitmap = mockk(relaxed = true)
        override fun close() {}
    }

    /** EngineLane's eager init folds several pref enums into its engine signature; every one needs a real value. */
    private fun colorizationPrefs(enabled: Boolean): TranslationPreferences = mockk(relaxed = true) {
        every { translationColorizationEnabled() } returns mockk {
            every { get() } returns enabled
        }
        every { translationAiEngine() } returns mockk {
            every { get() } returns AiEngine.entries.first()
        }
        every { translationEngineCategory() } returns mockk {
            every { get() } returns TranslationEngineCategory.entries.first()
        }
        every { translationStandardEngine() } returns mockk {
            every { get() } returns StandardEngine.entries.first()
        }
        every { translationReadingOrder() } returns mockk {
            every { get() } returns TranslationReadingOrder.AUTO
        }
        every { translationAiApiKey(any()) } returns stringPref()
        every { translationAiBaseUrlLmStudio() } returns stringPref()
        every { translationAiModel(any()) } returns stringPref()
        every { translationAiTemperature() } returns stringPref()
        every { translationAiOutputTokens() } returns stringPref()
    }

    private fun stringPref(): Preference<String> = mockk {
        every { get() } returns "test"
    }

    private fun newStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        artifactParent = FakeUniFile(parent = null, backing = backing),
        artifactFileName = "Chapter 1.json",
    )

    private fun newProcessor(prefs: TranslationPreferences): TranslationProcessor =
        TranslationProcessor(context, translationPreferences = prefs).apply {
            colorizationEngine = FakeEngine()
        }

    @BeforeEach
    fun stubPageDecode() {
        mockkObject(PageDecode)
    }

    @AfterEach
    fun unstubPageDecode() {
        unmockkObject(PageDecode)
    }

    private fun stubDecodeReturning(decoded: DecodedPage?) {
        coEvery {
            PageDecode.decodePageBitmapForTranslation(any(), any(), any(), any())
        } returns decoded
    }

    private fun decodedPage(): DecodedPage = DecodedPage(
        bitmap = mockk(relaxed = true) {
            every { width } returns 800
            every { height } returns 1200
        },
        sampleSize = 1,
        originalWidth = 800,
        originalHeight = 1200,
        decodeDecision = mockk(relaxed = true),
        sourceBytesSize = 128L,
        sourceFingerprint = "fp-1",
    )

    @Test
    fun `busy page reports PAGE_BUSY without running any work`() = runTest {
        val store = newStore()
        store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.BATCH)
        val processor = newProcessor(colorizationPrefs(enabled = true))
        stubDecodeReturning(decodedPage())

        val outcome = processor.colorizeOriginalPage(
            pageKey = "p1",
            streamFn = { error("decode must not run") },
            store = store,
            companionDir = null,
            origin = PageWriteOrigin.MANUAL,
        )

        outcome shouldBe PageColorizeOutcome.PAGE_BUSY
        store.state.value["p1"] shouldBe null
    }

    @Test
    fun `decode failure reports DECODE_FAILED, lands FAILED terminal, releases the lease`() = runTest {
        val store = newStore()
        val processor = newProcessor(colorizationPrefs(enabled = true))
        stubDecodeReturning(null)

        val outcome = processor.colorizeOriginalPage(
            pageKey = "p1",
            streamFn = { error("no bytes") },
            store = store,
            companionDir = null,
            origin = PageWriteOrigin.MANUAL,
        )

        outcome shouldBe PageColorizeOutcome.DECODE_FAILED
        // The RUNNING marker must not strand: it transitions to a terminal.
        store.state.value["p1"]?.coloringStatus shouldBe StageStatus.FAILED
        store.state.value["p1"]?.coloringError shouldBe "Page image could not be decoded"
        // The lease is released: a retry (or the pipeline) can own the page.
        store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
    }

    @Test
    fun `decode cancellation lands CANCELLED terminal and releases the lease`() = runTest {
        val store = newStore()
        val processor = newProcessor(colorizationPrefs(enabled = true))
        coEvery {
            PageDecode.decodePageBitmapForTranslation(any(), any(), any(), any())
        } throws CancellationException("chapter switched")

        val error = assertThrows<CancellationException> {
            processor.colorizeOriginalPage(
                pageKey = "p1",
                streamFn = { error("no bytes") },
                store = store,
                companionDir = null,
                origin = PageWriteOrigin.AUTO,
            )
        }

        error.message shouldBe "chapter switched"
        store.state.value["p1"]?.coloringStatus shouldBe StageStatus.CANCELLED
        store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
    }

    @Test
    fun `terminal write is skipped after the lease was taken over`() = runTest {
        // A store whose lease is re-owned by MANUAL while the AUTO run works.
        var currentToken = 1L
        val store = mockk<ChapterTranslationStore>(relaxed = true) {
            coEvery { tryAcquirePageStageLease(any(), any(), PageWriteOrigin.AUTO) } answers {
                LeaseAcquisition.Granted(mockk(relaxed = true) { every { token } returns currentToken })
            }
            every { pageLeases } returns java.util.concurrent.ConcurrentHashMap(
                mapOf(
                    "p1" to PageStageLeaseTable.PageLeaseRecord(
                        token = 2L,
                        origin = PageWriteOrigin.MANUAL,
                        stage = PageStage.Render,
                        generation = 0L,
                    ),
                ),
            )
            coEvery { markColorizeRunning(any(), any()) } answers {
                currentToken = 2L // take-over happens right after the RUNNING publish
                ChapterTranslationStore.PatchResult.Accepted(mockk(relaxed = true))
            }
        }
        val processor = newProcessor(colorizationPrefs(enabled = true))
        stubDecodeReturning(null)

        val outcome = processor.colorizeOriginalPage(
            pageKey = "p1",
            streamFn = { error("no bytes") },
            store = store,
            companionDir = null,
            origin = PageWriteOrigin.AUTO,
        )

        outcome shouldBe PageColorizeOutcome.DECODE_FAILED
        // The terminal transition must NOT have been written: MANUAL owns p1 now.
        coVerify(exactly = 1) { store.markColorizeRunning(any(), any()) }
        coVerify(exactly = 0) { store.updatePageFromCurrentSnapshot(any(), any(), any()) }
    }

    @Test
    fun `successful colorize commits READY state and releases the lease`() = runTest {
        val store = newStore()
        val processor = newProcessor(colorizationPrefs(enabled = true))
        stubDecodeReturning(decodedPage())

        val outcome = processor.colorizeOriginalPage(
            pageKey = "p1",
            streamFn = { error("decode is stubbed") },
            store = store,
            companionDir = null,
            origin = PageWriteOrigin.MANUAL,
        )

        outcome shouldBe PageColorizeOutcome.COMPLETED
        store.state.value["p1"]?.coloringStatus shouldBe StageStatus.READY
        store.state.value["p1"]?.coloredFromOriginal shouldBe true
        store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
    }

    @Test
    fun `defunct store is denied at the lease gate as PAGE_BUSY`() = runTest {
        val store = newStore()
        val processor = newProcessor(colorizationPrefs(enabled = true))
        stubDecodeReturning(decodedPage())
        store.markDefunct()

        val outcome = processor.colorizeOriginalPage(
            pageKey = "p1",
            streamFn = { error("decode is stubbed") },
            store = store,
            companionDir = null,
            origin = PageWriteOrigin.AUTO,
        )

        outcome shouldBe PageColorizeOutcome.PAGE_BUSY
    }

    @Test
    fun `rejected store commit reports FAILED instead of a lying COMPLETED`() = runTest {
        val store = mockk<ChapterTranslationStore>(relaxed = true) {
            coEvery { tryAcquirePageStageLease(any(), any(), any()) } returns LeaseAcquisition.Granted(
                mockk(relaxed = true) { every { token } returns 1L },
            )
            coEvery { markColorizeRunning(any(), any()) } returns ChapterTranslationStore.PatchResult.Accepted(
                mockk(relaxed = true),
            )
            coEvery {
                updatePageFromCurrentSnapshot(any(), any(), any())
            } returns ChapterTranslationStore.PatchResult.Rejected(reason = "forced rejection")
        }
        val processor = newProcessor(colorizationPrefs(enabled = true))
        stubDecodeReturning(decodedPage())

        val outcome = processor.colorizeOriginalPage(
            pageKey = "p1",
            streamFn = { error("decode is stubbed") },
            store = store,
            companionDir = null,
            origin = PageWriteOrigin.AUTO,
        )

        outcome shouldBe PageColorizeOutcome.FAILED
    }

    @Test
    fun `colorization disabled reports UNAVAILABLE without touching the page`() = runTest {
        val store = newStore()
        val processor = newProcessor(colorizationPrefs(enabled = false))
        stubDecodeReturning(decodedPage())

        val outcome = processor.colorizeOriginalPage(
            pageKey = "p1",
            streamFn = { error("must not decode") },
            store = store,
            companionDir = null,
            origin = PageWriteOrigin.MANUAL,
        )

        outcome shouldBe PageColorizeOutcome.UNAVAILABLE
        store.state.value["p1"] shouldBe null
    }
}
