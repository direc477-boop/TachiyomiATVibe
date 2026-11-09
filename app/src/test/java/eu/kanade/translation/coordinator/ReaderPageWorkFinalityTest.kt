package eu.kanade.translation.coordinator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Truth table for reader page-work finality: a colorize-only page is NEVER
 * final for reader dispatch — its OCR/translation statuses are placeholders,
 * so explicit taps and auto-translation demands both rework it (running
 * detection/render). Only genuinely translated or textless-terminal states
 * finalize page work.
 */
class ReaderPageWorkFinalityTest {

    private fun colorizeOnlyPage() = PageTranslation(
        sourceFileName = "p1.jpg",
        ocrStatus = StageStatus.SKIPPED,
        translationStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
        originalImageFallback = true,
        coloredFromOriginal = true,
        coloredImageName = "p1.color.jpg",
        coloringStatus = StageStatus.READY,
    )

    private fun translatedPage() = PageTranslation(
        sourceFileName = "p2.jpg",
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
    )

    private fun textlessPage() = PageTranslation(
        sourceFileName = "p3.jpg",
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
    )

    @Test
    fun `manual request reworks a colorize-only page even without force`() {
        isReaderPageWorkFinal(colorizeOnlyPage(), force = false) shouldBe false
    }

    @Test
    fun `auto-translation dispatch reworks a colorize-only page instead of skipping it`() {
        // A colorize-only page is not "already translated": the auto demand
        // must translate it, not skip on placeholder statuses.
        isReaderPageWorkFinal(colorizeOnlyPage(), force = false) shouldBe false
    }

    @Test
    fun `force always reworks regardless of stored state`() {
        isReaderPageWorkFinal(translatedPage(), force = true) shouldBe false
        isReaderPageWorkFinal(colorizeOnlyPage(), force = true) shouldBe false
    }

    @Test
    fun `genuinely translated and textless pages stay final`() {
        isReaderPageWorkFinal(translatedPage(), force = false) shouldBe true
        isReaderPageWorkFinal(textlessPage(), force = false) shouldBe true
    }

    @Test
    fun `null state is never final`() {
        isReaderPageWorkFinal(null, force = false) shouldBe false
    }
}
