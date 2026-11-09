package eu.kanade.translation.processing

import android.graphics.Bitmap
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.processing.DecodedPage
import eu.kanade.translation.processing.TranslationProcessor
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class BatchPageOperationTest {

    private val processor: TranslationProcessor = mockk(relaxed = true) {
        every { activeUpscalingEngine() } returns null
    }
    private val recognitionEngine: PageRecognitionEngine = mockk(relaxed = true)
    private val translator: TextTranslator = mockk(relaxed = true)

    private val coordinator = BatchPageOperation(
        processor = processor,
        recognitionEngineProvider = { recognitionEngine },
        translatorProvider = { translator },
    )

    @Test
    fun `textless page is colorized from its original during the full batch`() = runTest {
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }
        val decoded = DecodedPage(
            bitmap = mockk(relaxed = true),
            sampleSize = 1,
            originalWidth = 800,
            originalHeight = 1200,
            decodeDecision = mockk(relaxed = true),
            sourceBytesSize = 1000L,
            sourceFingerprint = "sha-textless",
        )
        coEvery { processor.decodeImage("p1.jpg", any(), any()) } returns decoded
        val textlessPage = PageTranslation(
            sourceFileName = "p1.jpg",
            blocks = mutableListOf(),
            ocrStatus = StageStatus.READY,
            sourceFingerprint = "sha-textless",
        )
        coEvery { processor.analyze("p1.jpg", any(), any()) } returns textlessPage
        every { processor.activeColorizationEngine() } returns mockk(relaxed = true)

        val progress = coordinator.execute(
            chapterKey = "ch1",
            orderedPageKeys = listOf("p1.jpg"),
            streamProvider = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
            progress = MutableStateFlow(BatchPageOperation.Progress("ch1", 0, 1, "INITIALIZING")),
        )

        progress.isFinished shouldBe true
        // Detection found no text, yet the page still gets colored from its original.
        coVerify(exactly = 1) { processor.colorizeOriginalPage(eq("p1.jpg"), any(), any(), any(), any()) }
    }

    @Test
    fun `textless page with identity-verified colored result is not recolored`() = runTest {
        val prior = PageTranslation(
            sourceFileName = "p1.jpg",
            blocks = mutableListOf(),
            ocrStatus = StageStatus.SKIPPED,
            translationStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            originalImageFallback = true,
            coloredFromOriginal = true,
            coloredImageName = "p1.color.jpg",
            coloringStatus = StageStatus.READY,
            sourceFingerprint = "sha-textless",
        )
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(mapOf("p1.jpg" to prior))
        }
        val decoded = DecodedPage(
            bitmap = mockk(relaxed = true),
            sampleSize = 1,
            originalWidth = 800,
            originalHeight = 1200,
            decodeDecision = mockk(relaxed = true),
            sourceBytesSize = 1000L,
            sourceFingerprint = "sha-textless",
        )
        coEvery { processor.decodeImage("p1.jpg", any(), any()) } returns decoded
        val textlessPage = PageTranslation(
            sourceFileName = "p1.jpg",
            blocks = mutableListOf(),
            ocrStatus = StageStatus.READY,
            sourceFingerprint = "sha-textless",
        )
        coEvery { processor.analyze("p1.jpg", any(), any()) } returns textlessPage
        every { processor.activeColorizationEngine() } returns mockk(relaxed = true)

        val progress = coordinator.execute(
            chapterKey = "ch1",
            orderedPageKeys = listOf("p1.jpg"),
            streamProvider = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
            progress = MutableStateFlow(BatchPageOperation.Progress("ch1", 0, 1, "INITIALIZING")),
        )

        progress.isFinished shouldBe true
        // Same source bytes + valid saved coloring: the engine must NOT rerun.
        coVerify(exactly = 0) { processor.colorizeOriginalPage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `stale colored result for textless page is recolored`() = runTest {
        val prior = PageTranslation(
            sourceFileName = "p1.jpg",
            blocks = mutableListOf(),
            ocrStatus = StageStatus.SKIPPED,
            translationStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            originalImageFallback = true,
            coloredFromOriginal = true,
            coloredImageName = "p1.color.jpg",
            coloringStatus = StageStatus.READY,
            sourceFingerprint = "sha-old",
        )
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(mapOf("p1.jpg" to prior))
        }
        val decoded = DecodedPage(
            bitmap = mockk(relaxed = true),
            sampleSize = 1,
            originalWidth = 800,
            originalHeight = 1200,
            decodeDecision = mockk(relaxed = true),
            sourceBytesSize = 1000L,
            sourceFingerprint = "sha-new",
        )
        coEvery { processor.decodeImage("p1.jpg", any(), any()) } returns decoded
        val textlessPage = PageTranslation(
            sourceFileName = "p1.jpg",
            blocks = mutableListOf(),
            ocrStatus = StageStatus.READY,
            sourceFingerprint = "sha-new",
        )
        coEvery { processor.analyze("p1.jpg", any(), any()) } returns textlessPage
        every { processor.activeColorizationEngine() } returns mockk(relaxed = true)

        val progress = coordinator.execute(
            chapterKey = "ch1",
            orderedPageKeys = listOf("p1.jpg"),
            streamProvider = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
            progress = MutableStateFlow(BatchPageOperation.Progress("ch1", 0, 1, "INITIALIZING")),
        )

        progress.isFinished shouldBe true
        // The page's bytes changed: the old colored result is stale and must be rerun.
        coVerify(exactly = 1) { processor.colorizeOriginalPage(eq("p1.jpg"), any(), any(), any(), any()) }
    }

    @Test
    fun `text-present page recolors exactly once from the cleaned base, never from the original`() = runTest {
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }
        val decoded = DecodedPage(
            bitmap = mockk(relaxed = true),
            sampleSize = 1,
            originalWidth = 800,
            originalHeight = 1200,
            decodeDecision = mockk(relaxed = true),
            sourceBytesSize = 1000L,
            sourceFingerprint = "sha-text",
        )
        coEvery { processor.decodeImage("p1.jpg", any(), any()) } returns decoded
        val textPage = PageTranslation(
            sourceFileName = "p1.jpg",
            blocks = mutableListOf(createBlock("Hello")),
            ocrStatus = StageStatus.READY,
            sourceFingerprint = "sha-text",
        )
        coEvery { processor.analyze("p1.jpg", any(), any()) } returns textPage
        // Inpaint produces the cleaned base the color stage consumes.
        val cleanedStub: Bitmap = mockk(relaxed = true)
        coEvery { processor.inpaint(any(), any(), any(), any(), any()) } answers {
            (args[2] as PageTranslation).apply {
                cleanedBitmap = cleanedStub
                inpaintStatus = StageStatus.READY
            }
            cleanedStub
        }
        every { processor.activeColorizationEngine() } returns mockk(relaxed = true)

        val progress = coordinator.execute(
            chapterKey = "ch1",
            orderedPageKeys = listOf("p1.jpg"),
            streamProvider = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
            progress = MutableStateFlow(BatchPageOperation.Progress("ch1", 0, 1, "INITIALIZING")),
        )

        progress.isFinished shouldBe true
        // Current contract: the model reruns ONCE, on the CLEANED bitmap
        // (base == the inpainted bitmap, fromOriginal=false) — chroma
        // reuse would flip this contract observably.
        val baseSlot = slot<Bitmap>()
        coVerify(exactly = 1) {
            processor.colorize(eq("p1.jpg"), any(), any(), any(), capture(baseSlot), eq(false))
        }
        baseSlot.captured shouldBe cleanedStub
    }

    private fun createBlock(text: String) = TranslationBlock(
        text = text,
        translation = "",
        width = 100f,
        height = 50f,
        x = 10f,
        y = 10f,
        symHeight = 50f,
        symWidth = 100f,
        angle = 0f,
    )

    @Test
    fun `translateChapter skips translation-ready page without display readiness`() = runTest {
        val completedPage = PageTranslation(
            sourceFileName = "p1.jpg",
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.FAILED,
            blocks = mutableListOf(createBlock("Done").apply { translation = "Translated" }),
        )

        val stateMap = mapOf("p1.jpg" to completedPage.toPublishedPage())
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(stateMap)
        }

        val progress = coordinator.execute(
            chapterKey = "ch1",
            orderedPageKeys = listOf("p1.jpg"),
            streamProvider = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
            progress = MutableStateFlow(BatchPageOperation.Progress("ch1", 0, 1, "INITIALIZING")),
        )

        progress.isFinished shouldBe true
        progress.completedPages shouldBe 1
        progress.totalPages shouldBe 1
        // Since p1.jpg was already complete, analyze is never called!
        coVerify(exactly = 0) { processor.analyze(any(), any(), any()) }
    }

    @Test
    fun `translateChapter runs OCR barrier and translates uncompleted pages`() = runTest {
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }

        val mockBitmap: Bitmap = mockk(relaxed = true)
        val decoded = DecodedPage(
            bitmap = mockBitmap,
            sampleSize = 1,
            originalWidth = 800,
            originalHeight = 1200,
            decodeDecision = mockk(relaxed = true),
            sourceBytesSize = 1000L,
            sourceFingerprint = "sha123",
        )

        coEvery { processor.decodeImage("p1.jpg", any(), any()) } returns decoded
        every { processor.activeUpscalingEngine() } returns null

        val pageWithBlock = PageTranslation(
            sourceFileName = "p1.jpg",
            blocks = mutableListOf(createBlock("Hello")),
            ocrStatus = StageStatus.READY,
        )
        coEvery { processor.analyze("p1.jpg", mockBitmap, recognitionEngine) } returns pageWithBlock

        val progress = coordinator.execute(
            chapterKey = "ch1",
            orderedPageKeys = listOf("p1.jpg"),
            streamProvider = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
            progress = MutableStateFlow(BatchPageOperation.Progress("ch1", 0, 1, "INITIALIZING")),
            inpaintingEnabled = false,
        )

        progress.isFinished shouldBe true
        progress.completedPages shouldBe 1
        progress.totalPages shouldBe 1
        coVerify(exactly = 1) { processor.analyze("p1.jpg", mockBitmap, recognitionEngine) }
        coVerify(exactly = 1) { processor.translateBatch(any(), translator) }
    }

    @Test
    fun `executeColorizeOnly runs upscaling when activeUpscalingEngine is present`() = runTest {
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
            coEvery { updatePageFromCurrentSnapshot(any(), any(), any()) } returns
                ChapterTranslationStore.PatchResult.Accepted(mockk(relaxed = true))
        }
        val mockBitmap: Bitmap = mockk(relaxed = true)
        val decoded = DecodedPage(
            bitmap = mockBitmap,
            sampleSize = 1,
            originalWidth = 800,
            originalHeight = 1200,
            decodeDecision = mockk(relaxed = true),
            sourceBytesSize = 1000L,
            sourceFingerprint = "sha-color",
        )
        coEvery { processor.decodeImage("p1.jpg", any(), any()) } returns decoded
        every { processor.activeColorizationEngine() } returns mockk(relaxed = true)
        val upscaler: eu.kanade.translation.engines.upscaling.PageUpscalingEngine = mockk(relaxed = true)
        every { processor.activeUpscalingEngine() } returns upscaler
        coEvery { processor.colorize("p1.jpg", any(), any(), any(), any(), true) } answers {
            val page = args[1] as PageTranslation
            page.coloringStatus = StageStatus.READY
            page.coloredBitmap = mockBitmap
            mockBitmap
        }

        val progress = coordinator.execute(
            chapterKey = "ch1",
            orderedPageKeys = listOf("p1.jpg"),
            streamProvider = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
            progress = MutableStateFlow(BatchPageOperation.Progress("ch1", 0, 1, "INITIALIZING")),
            colorizeOnly = true,
        )

        progress.isFinished shouldBe true
        progress.completedPages shouldBe 1
        coVerify(exactly = 1) { processor.upscale("p1.jpg", any(), upscaler, any(), any()) }
        coVerify(exactly = 1) { processor.releasePageBitmaps(any()) }
    }

    @Test
    fun `colorize chapter reports an upscaling failure instead of completing`() = runTest {
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
            coEvery { updatePageFromCurrentSnapshot(any(), any(), any()) } returns
                ChapterTranslationStore.PatchResult.Accepted(mockk(relaxed = true))
        }
        val bitmap: Bitmap = mockk(relaxed = true)
        coEvery { processor.decodeImage("p1.jpg", any(), any()) } returns DecodedPage(
            bitmap = bitmap,
            sampleSize = 1,
            originalWidth = 800,
            originalHeight = 1200,
            decodeDecision = mockk(relaxed = true),
            sourceBytesSize = 1000L,
            sourceFingerprint = "sha-color",
        )
        every { processor.activeColorizationEngine() } returns mockk(relaxed = true)
        val upscaler: eu.kanade.translation.engines.upscaling.PageUpscalingEngine = mockk(relaxed = true)
        every { processor.activeUpscalingEngine() } returns upscaler
        coEvery { processor.colorize("p1.jpg", any(), any(), any(), any(), true) } answers {
            (args[1] as PageTranslation).coloringStatus = StageStatus.READY
            bitmap
        }
        coEvery { processor.upscale("p1.jpg", any(), upscaler, any(), any()) } throws IllegalStateException("upscaler failed")

        val result = coordinator.execute(
            chapterKey = "ch1",
            orderedPageKeys = listOf("p1.jpg"),
            streamProvider = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
            progress = MutableStateFlow(BatchPageOperation.Progress("ch1", 0, 1, "INITIALIZING")),
            colorizeOnly = true,
        )

        result.currentStage shouldBe "FAILED"
        result.completedPages shouldBe 0
        result.error shouldBe "Failed to colorize page p1.jpg: upscaler failed"
    }
}
