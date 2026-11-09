package eu.kanade.translation.processing

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.engines.coloring.PageColorizationEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Hard-fail surfacing at the processor boundary: a reported init cause must become a FAILED stage. */
class TranslationProcessorColorizationStageTest {

    private class FakeEngine(
        private val available: Boolean,
        private val initError: String?,
    ) : PageColorizationEngine {
        override fun isAvailable(): Boolean = available
        override fun currentStamp(): String? = null
        override fun initializationError(): String? = initError
        override fun colorize(source: Bitmap, seed: Long): Bitmap = error("unreachable")
        override fun close() {}
    }

    @Test
    fun `reported initialization error marks the stage failed with the cause`() = runTest {
        val processor = TranslationProcessor(mockk<Context>(relaxed = true))
        val page = PageTranslation()
        val cause = "Qualcomm NPU dispatch libraries missing from /data/app: libQnnHtp.so"

        val result = processor.colorize(
            "page-1",
            page,
            FakeEngine(available = false, initError = cause),
            base = mockk<Bitmap>(relaxed = true),
        )

        result shouldBe null
        page.coloringStatus shouldBe StageStatus.FAILED
        page.coloringError shouldContain cause
    }

    @Test
    fun `unavailable engine without a cause stays a silent skip`() = runTest {
        val processor = TranslationProcessor(mockk<Context>(relaxed = true))
        val page = PageTranslation()

        val result = processor.colorize(
            "page-1",
            page,
            FakeEngine(available = false, initError = null),
            base = mockk<Bitmap>(relaxed = true),
        )

        result shouldBe null
        page.coloringStatus shouldBe StageStatus.SKIPPED
        page.coloringError shouldBe null
    }

    @Test
    fun `inference failure marks the stage failed without any demotion`() = runTest {
        val processor = TranslationProcessor(mockk<Context>(relaxed = true))
        val page = PageTranslation()
        val engine = FakeEngine(available = true, initError = null)

        val result = processor.colorize("page-1", page, engine, base = mockk<Bitmap>(relaxed = true))

        result shouldBe null
        page.coloringStatus shouldBe StageStatus.FAILED
        page.coloringError shouldBe "unreachable"
    }
}
