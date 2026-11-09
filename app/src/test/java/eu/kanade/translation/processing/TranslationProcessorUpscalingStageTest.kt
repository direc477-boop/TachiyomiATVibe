package eu.kanade.translation.processing

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.engines.upscaling.PageUpscalingEngine
import eu.kanade.translation.engines.upscaling.UnprovisionedUpscalingEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TranslationProcessorUpscalingStageTest {

    private class FakeUpscalingEngine(
        private val available: Boolean,
        private val initError: String?,
        private val onUpscale: (Bitmap) -> Bitmap = { it },
    ) : PageUpscalingEngine {
        override fun isAvailable(): Boolean = available
        override fun initializationError(): String? = initError
        override fun upscalePage(source: Bitmap): Bitmap = onUpscale(source)
        override fun close() {}
    }

    @Test
    fun `unprovisioned engine fails fast with visible error and zero silent fallback`() = runTest {
        val processor = TranslationProcessor(mockk<Context>(relaxed = true))
        val page = PageTranslation()
        val unprovisioned = UnprovisionedUpscalingEngine("SM8650 NPU validation pending")

        val exception = shouldThrow<IllegalStateException> {
            processor.upscale(
                pageKey = "page-1",
                page = page,
                upscalingEngine = unprovisioned,
                base = mockk<Bitmap>(relaxed = true),
            )
        }

        exception.message shouldContain "SM8650 NPU validation pending"
    }

    @Test
    fun `unavailable engine throws explicit exception on upscale invocation`() = runTest {
        val processor = TranslationProcessor(mockk<Context>(relaxed = true))
        val page = PageTranslation()
        val cause = "Qualcomm dispatch library libQnnHtp.so missing"
        val engine = FakeUpscalingEngine(available = false, initError = cause)

        val exception = shouldThrow<IllegalStateException> {
            processor.upscale(
                pageKey = "page-1",
                page = page,
                upscalingEngine = engine,
                base = mockk<Bitmap>(relaxed = true),
            )
        }

        exception.message shouldContain cause
    }

    @Test
    fun `successful upscale preserves original overlay geometry and assigns upscaled bitmap`() = runTest {
        val processor = TranslationProcessor(mockk<Context>(relaxed = true))
        val initialInput = mockk<Bitmap>(relaxed = true) {
            every { width } returns 1000
            every { height } returns 1500
        }
        val upscaledOutput = mockk<Bitmap>(relaxed = true) {
            every { width } returns 4000
            every { height } returns 6000
        }
        val page = PageTranslation(
            imgWidth = 1000f,
            imgHeight = 1500f,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "Hello",
                    x = 10f,
                    y = 20f,
                    width = 100f,
                    height = 50f,
                    symWidth = 100f,
                    symHeight = 50f,
                    angle = 0f,
                ),
            ),
        )
        page.coloredBitmap = initialInput

        val engine = FakeUpscalingEngine(
            available = true,
            initError = null,
            onUpscale = { upscaledOutput },
        )

        val result = processor.upscale(
            pageKey = "page-1",
            page = page,
            upscalingEngine = engine,
            base = initialInput,
        )

        result shouldBe upscaledOutput
        page.coloredBitmap shouldBe upscaledOutput
        page.imgWidth shouldBe 1000f
        page.imgHeight shouldBe 1500f
        page.blocks[0].x shouldBe 10f
        page.blocks[0].y shouldBe 20f
        page.blocks[0].width shouldBe 100f
        page.blocks[0].height shouldBe 50f
    }

    @Test
    fun `fatal OutOfMemoryError is rethrown directly per VM contract`() = runTest {
        val processor = TranslationProcessor(mockk<Context>(relaxed = true))
        val page = PageTranslation()
        val engine = FakeUpscalingEngine(
            available = true,
            initError = null,
            onUpscale = { throw OutOfMemoryError("Failed to allocate 192MB bitmap") },
        )

        shouldThrow<OutOfMemoryError> {
            processor.upscale(
                pageKey = "page-1",
                page = page,
                upscalingEngine = engine,
                base = mockk<Bitmap>(relaxed = true),
            )
        }
    }
}
