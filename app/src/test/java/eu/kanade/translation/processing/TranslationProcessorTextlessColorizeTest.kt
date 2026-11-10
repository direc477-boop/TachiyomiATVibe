package eu.kanade.translation.processing

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.translation.engines.coloring.PageColorizationEngine
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.upscaling.PageUpscalingEngine
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isTextlessTerminal
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.TranslationReadingOrder
import java.io.ByteArrayInputStream

class TranslationProcessorTextlessColorizeTest {

    private val context: Context = mockk(relaxed = true)

    @BeforeEach
    fun stubPageDecode() {
        mockkObject(PageDecode)
        coEvery {
            PageDecode.decodePageBitmapForTranslation(any(), any(), any(), any())
        } returns DecodedPage(
            bitmap = mockk(relaxed = true) {
                every { width } returns 800
                every { height } returns 1200
            },
            sampleSize = 1,
            originalWidth = 800,
            originalHeight = 1200,
            decodeDecision = mockk(relaxed = true),
            sourceBytesSize = 128L,
            sourceFingerprint = "fp-textless",
        )
    }

    @AfterEach
    fun unstubPageDecode() {
        unmockkObject(PageDecode)
    }

    @Test
    fun `textless page colorizes original when colorization is enabled`() = runBlocking<Unit> {
        val publication = mockk<CleanedPublication>(relaxed = true)
        every {
            publication.writeVerifiedColoredImage(any(), any(), any())
        } returns CleanedPublication.CleanedImageWriteResult("p1.colored.v1.jpg", "sha-color")
        val processor = TranslationProcessor(
            context = context,
            translationPreferences = prefs(colorizationEnabled = true, upscalingEnabled = false),
            suppliedCleanedPublication = publication,
        ).apply {
            colorizationEngine = FakeColorizationEngine(mockk(relaxed = true))
        }
        val signals = mutableListOf<TranslationStageSignal>()
        val translator = mockk<TextTranslator>(relaxed = true)

        val result = processor.processSinglePage(
            pageKey = "p1.jpg",
            streamFn = { ByteArrayInputStream(byteArrayOf(1)) },
            recognitionEngine = textlessRecognitionEngine(),
            translator = translator,
            companionDir = mockk<UniFile>(relaxed = true),
            stageReporter = { signals += it },
        )

        val success = result.shouldBeInstanceOf<TranslationProcessor.ProcessResult.Success>()
        success.page.isTextlessTerminal shouldBe true
        success.page.ocrStatus shouldBe StageStatus.READY
        success.page.translationStatus shouldBe StageStatus.SKIPPED
        success.page.inpaintStatus shouldBe StageStatus.SKIPPED
        success.page.coloringStatus shouldBe StageStatus.READY
        success.page.coloredFromOriginal shouldBe true
        success.page.coloredImageName shouldBe "p1.colored.v1.jpg"
        signals shouldContain TranslationStageSignal.COLORING
        signals shouldContain TranslationStageSignal.FINISHING
        coVerify(exactly = 0) { translator.translatePage(any(), any()) }
    }

    @Test
    fun `textless page skips colorization when preference is off`() = runBlocking<Unit> {
        val colorEngine = FakeColorizationEngine(mockk(relaxed = true))
        val processor = TranslationProcessor(
            context = context,
            translationPreferences = prefs(colorizationEnabled = false, upscalingEnabled = false),
        ).apply {
            colorizationEngine = colorEngine
        }
        val signals = mutableListOf<TranslationStageSignal>()

        val result = processor.processSinglePage(
            pageKey = "p1.jpg",
            streamFn = { ByteArrayInputStream(byteArrayOf(1)) },
            recognitionEngine = textlessRecognitionEngine(),
            translator = mockk(relaxed = true),
            stageReporter = { signals += it },
        )

        val success = result.shouldBeInstanceOf<TranslationProcessor.ProcessResult.Success>()
        success.page.isTextlessTerminal shouldBe true
        success.page.coloringStatus shouldBe StageStatus.PENDING
        success.page.coloredFromOriginal shouldBe false
        colorEngine.calls shouldBe 0
        signals shouldNotContain TranslationStageSignal.COLORING
    }

    @Test
    fun `textless page defers publication to upscaler when upscaling is enabled`() = runBlocking<Unit> {
        val coloredBitmap = mockk<Bitmap>(relaxed = true)
        val upscaledBitmap = mockk<Bitmap>(relaxed = true)
        val publication = mockk<CleanedPublication>(relaxed = true)
        every {
            publication.writeVerifiedColoredImage(any(), any(), upscaledBitmap)
        } returns CleanedPublication.CleanedImageWriteResult("p1.colored.upscaled.jpg", "sha-up")
        val colorEngine = FakeColorizationEngine(coloredBitmap)
        val upscaler = FakeUpscalingEngine { upscaledBitmap }
        val processor = TranslationProcessor(
            context = context,
            translationPreferences = prefs(colorizationEnabled = true, upscalingEnabled = true),
            suppliedCleanedPublication = publication,
        ).apply {
            colorizationEngine = colorEngine
            upscalingEngine = upscaler
        }

        val result = processor.processSinglePage(
            pageKey = "p1.jpg",
            streamFn = { ByteArrayInputStream(byteArrayOf(1)) },
            recognitionEngine = textlessRecognitionEngine(),
            translator = mockk(relaxed = true),
            companionDir = mockk<UniFile>(relaxed = true),
        )

        val success = result.shouldBeInstanceOf<TranslationProcessor.ProcessResult.Success>()
        success.page.isTextlessTerminal shouldBe true
        success.page.coloringStatus shouldBe StageStatus.READY
        success.page.coloredImageName shouldBe "p1.colored.upscaled.jpg"
        verify(exactly = 1) { publication.writeVerifiedColoredImage(any(), "p1.jpg", upscaledBitmap) }
        verify(exactly = 0) { publication.writeVerifiedColoredImage(any(), "p1.jpg", coloredBitmap) }
    }

    @Test
    fun `textless upscale failure isolates failure to coloringStatus`() = runBlocking<Unit> {
        val processor = TranslationProcessor(
            context = context,
            translationPreferences = prefs(colorizationEnabled = true, upscalingEnabled = true),
        ).apply {
            colorizationEngine = FakeColorizationEngine(mockk(relaxed = true))
            upscalingEngine = FakeUpscalingEngine { throw IllegalStateException("upscale boom") }
        }

        val result = processor.processSinglePage(
            pageKey = "p1.jpg",
            streamFn = { ByteArrayInputStream(byteArrayOf(1)) },
            recognitionEngine = textlessRecognitionEngine(),
            translator = mockk(relaxed = true),
        )

        val success = result.shouldBeInstanceOf<TranslationProcessor.ProcessResult.Success>()
        success.page.isTextlessTerminal shouldBe true
        success.page.coloringStatus shouldBe StageStatus.FAILED
        success.page.coloringError shouldBe "upscale boom"
    }

    @Test
    fun `textless upscale timeout marks coloringStatus failed without mutating translationStatus`() = runBlocking<Unit> {
        val processor = TranslationProcessor(
            context = context,
            translationPreferences = prefs(colorizationEnabled = true, upscalingEnabled = true),
        ).apply {
            colorizationEngine = FakeColorizationEngine(mockk(relaxed = true))
            upscalingEngine = FakeUpscalingEngine {
                runBlocking { withTimeout(1) { delay(50) } }
                error("unreachable")
            }
        }

        val result = processor.processSinglePage(
            pageKey = "p1.jpg",
            streamFn = { ByteArrayInputStream(byteArrayOf(1)) },
            recognitionEngine = textlessRecognitionEngine(),
            translator = mockk(relaxed = true),
        )

        val failure = result.shouldBeInstanceOf<TranslationProcessor.ProcessResult.Failure>()
        failure.failedStage shouldBe "UPSCALE"
        failure.page?.isTextlessTerminal shouldBe true
        failure.page?.translationStatus shouldBe StageStatus.SKIPPED
        failure.page?.coloringStatus shouldBe StageStatus.FAILED
    }

    private fun textlessRecognitionEngine(): PageRecognitionEngine = mockk(relaxed = true) {
        coEvery { analyze(any()) } returns PageTranslation(blocks = mutableListOf())
    }

    private fun prefs(
        colorizationEnabled: Boolean,
        upscalingEnabled: Boolean,
    ): TranslationPreferences = mockk(relaxed = true) {
        every { translationColorizationEnabled() } returns mockk {
            every { get() } returns colorizationEnabled
        }
        every { translationUpscalingEnabled() } returns mockk {
            every { get() } returns upscalingEnabled
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

    private class FakeColorizationEngine(
        private val output: Bitmap,
    ) : PageColorizationEngine {
        var calls = 0
        override fun isAvailable(): Boolean = true
        override fun currentStamp(): String? = "color-stamp"
        override fun initializationError(): String? = null
        override fun colorize(source: Bitmap, seed: Long): Bitmap {
            calls++
            return output
        }
        override fun close() {}
    }

    private class FakeUpscalingEngine(
        private val onUpscale: (Bitmap) -> Bitmap,
    ) : PageUpscalingEngine {
        override fun isAvailable(): Boolean = true
        override fun initializationError(): String? = null
        override fun upscalePage(source: Bitmap): Bitmap = onUpscale(source)
        override fun close() {}
    }
}
