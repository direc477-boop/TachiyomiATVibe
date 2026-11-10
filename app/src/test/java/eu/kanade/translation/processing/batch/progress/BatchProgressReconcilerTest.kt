package eu.kanade.translation.processing.batch.progress

import eu.kanade.translation.diagnostics.BatchDiagnosticStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.processing.batch.BatchPass1Outcome
import eu.kanade.translation.processing.batch.BatchPass1Status
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchProgressReconcilerTest {

    @Test
    fun `retryable pause preserves prefix and leaves tail pending without stranded failures`() {
        val prefix = readyPage("p0")
        val anchor = PageTranslation(
            sourceFileName = "p1",
            blocks = mutableListOf(block()),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.PARTIAL,
            inpaintStatus = StageStatus.READY,
        )
        val result = BatchProgressReconciler.reconcile(
            pageMap = mapOf("p0" to prefix, "p1" to anchor),
            orderedKeys = listOf("p0", "p1", "p2"),
            activeGeneration = 0L,
            pauseOutcome = BatchPass1Outcome(
                needsTranslation = listOf("p0", "p1"),
                status = BatchPass1Status.PAUSED,
                anchorPageKey = "p1",
                completedPageKeys = setOf("p0"),
                retryablePageKeys = setOf("p1"),
                nextEligibleRetryAtEpochMs = 5000L,
                reason = "provider quota exhausted",
            ),
        )

        result.chapterStatus shouldBe Translation.State.PAUSED
        result.paused shouldBe true
        result.strandedPages shouldBe emptyMap()
        result.doneCount shouldBe 1
        result.partialCount shouldBe 1
        result.pendingCount shouldBe 1
        result.failedCount shouldBe 0
        result.retryableCount shouldBe 1
        result.nextEligibleRetryAtEpochMs shouldBe 5000L
    }

    @Test
    fun `persistence rejection is a retryable error without durable tail failures`() {
        //  field fix (Chapter 21): the old in-memory READY_WITH_WARNINGS
        // classification rendered the chapter as completed with no Retry
        // affordance while the tail pages were never resolved.
        val result = BatchProgressReconciler.reconcile(
            pageMap = mapOf("p0" to readyPage("p0")),
            orderedKeys = listOf("p0", "p1"),
            activeGeneration = 0L,
            pauseOutcome = BatchPass1Outcome(
                needsTranslation = listOf("p0", "p1"),
                status = BatchPass1Status.PERSISTENCE_REJECTED,
                anchorPageKey = "p1",
                completedPageKeys = setOf("p0"),
                persistenceRejectedStage = BatchDiagnosticStage.TRANSLATION,
                reason = "Batch persistence publication rejected",
            ),
        )

        result.chapterStatus shouldBe Translation.State.ERROR
        result.paused shouldBe false
        result.nonDurableFailure shouldBe true
        result.failedCount shouldBe 0
        result.pendingCount shouldBe 1
        result.retryableCount shouldBe 0
    }

    @Test
    fun `a partial candidate with no rendered result is unresolved work, not a warning`() {
        val partialUnrendered = PageTranslation(
            sourceFileName = "p1",
            blocks = mutableListOf(block()),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.PARTIAL,
            inpaintStatus = StageStatus.READY,
        )
        val result = BatchProgressReconciler.reconcile(
            pageMap = mapOf("p0" to readyPage("p0"), "p1" to partialUnrendered),
            orderedKeys = listOf("p0", "p1"),
            activeGeneration = 0L,
        )

        result.chapterStatus shouldBe Translation.State.ERROR
        result.failedCount shouldBe 1
        result.partialCount shouldBe 1
        result.strandedPages.keys shouldBe setOf("p1")
    }

    private fun colorizeOnlyPage(key: String, coloringStatus: String) = PageTranslation(
        sourceFileName = key,
        ocrStatus = StageStatus.SKIPPED,
        translationStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
        originalImageFallback = true,
        coloredFromOriginal = true,
        coloringStatus = coloringStatus,
    )

    @Test
    fun `colorize-only run with isolated coloring failures completes with warnings`() {
        val pages = mapOf(
            "p0" to colorizeOnlyPage("p0", StageStatus.READY),
            "p1" to colorizeOnlyPage("p1", StageStatus.FAILED),
            "p2" to colorizeOnlyPage("p2", StageStatus.READY),
        )
        val result = BatchProgressReconciler.reconcileFlaggedCompleted(
            pageMap = pages,
            orderedKeys = listOf("p0", "p1", "p2"),
            activeGeneration = 0L,
            includeColoringFailures = true,
        )

        result.chapterStatus shouldBe Translation.State.READY_WITH_WARNINGS
        result.doneCount shouldBe 2
        result.failedCount shouldBe 1
    }

    @Test
    fun `colorize-only run where nothing colored is a retryable error`() {
        val pages = mapOf(
            "p0" to colorizeOnlyPage("p0", StageStatus.FAILED),
            "p1" to colorizeOnlyPage("p1", StageStatus.FAILED),
        )
        val result = BatchProgressReconciler.reconcileFlaggedCompleted(
            pageMap = pages,
            orderedKeys = listOf("p0", "p1"),
            activeGeneration = 0L,
            includeColoringFailures = true,
        )

        result.chapterStatus shouldBe Translation.State.ERROR
        result.failedCount shouldBe 2
    }

    @Test
    fun `translation lane still reports a clean TRANSLATED chapter without the coloring flag`() {
        // The translation lane's coloring failures intentionally never
        // re-fail a page; without includeColoringFailures the chapter
        // projection must stay TRANSLATED (existing contract).
        val pages = mapOf(
            "p0" to readyPage("p0"),
            "p1" to colorizeOnlyPage("p1", StageStatus.FAILED),
        )
        val result = BatchProgressReconciler.reconcileFlaggedCompleted(
            pageMap = pages,
            orderedKeys = listOf("p0", "p1"),
            activeGeneration = 0L,
        )

        result.chapterStatus shouldBe Translation.State.TRANSLATED
    }

    private fun readyPage(key: String) = PageTranslation(
        sourceFileName = key,
        blocks = mutableListOf(block()),
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "$key.cleaned.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun block() = TranslationBlock(
        text = "source",
        translation = "target",
        width = 10f,
        height = 10f,
        x = 1f,
        y = 1f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )
}
