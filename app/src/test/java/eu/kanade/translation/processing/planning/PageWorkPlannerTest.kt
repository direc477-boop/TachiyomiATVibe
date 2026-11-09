package eu.kanade.translation.processing.planning

import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.ArtifactOrigin
import eu.kanade.translation.persistence.ArtifactStage
import eu.kanade.translation.persistence.ArtifactStageStatus
import eu.kanade.translation.persistence.DurableFailureMetadata
import eu.kanade.translation.persistence.FailureCategory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class PageWorkPlannerTest {

    @Test
    fun `complete chapter reuses every stage without expensive work`() {
        val expected = fingerprints()
        val pages = listOf("page-1", "page-2", "page-3").map { key ->
            BatchPlannerInput(
                pageKey = key,
                page = completePage(key, expected),
                expectedFingerprints = expected,
            )
        }

        val plan = PageWorkPlanner.planChapter(pages)

        plan.pages.map { it.pageKey } shouldContainExactly listOf("page-1", "page-2", "page-3")
        plan.firstWorkPageKey shouldBe null
        plan.pages.forEach { page ->
            page.stages.map { it.decision } shouldContainExactly List(5) { StageDecision.REUSE }
            page.displayReady shouldBe true
        }
    }

    @Test
    fun `target language change reruns translation and waits layout only`() {
        val old = fingerprints()
        val current = old.copy(translation = "translation-new")
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = completePage("page-1", old),
                expectedFingerprints = current,
            ),
        )
        plan.decisions() shouldBe mapOf(
            BatchStage.DETECTION to StageDecision.REUSE,
            BatchStage.OCR to StageDecision.REUSE,
            BatchStage.INPAINT to StageDecision.REUSE,
            BatchStage.TRANSLATION to StageDecision.RUN,
            BatchStage.LAYOUT to StageDecision.WAIT_FOR_DEPENDENCY,
        )
    }

    @Test
    fun `inpaint change reruns inpaint and layout but reuses translation`() {
        val old = fingerprints()
        val current = old.copy(inpaint = "inpaint-new")
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = completePage("page-1", old),
                expectedFingerprints = current,
            ),
        )

        plan.decisions() shouldBe mapOf(
            BatchStage.DETECTION to StageDecision.REUSE,
            BatchStage.OCR to StageDecision.REUSE,
            BatchStage.INPAINT to StageDecision.RUN,
            BatchStage.TRANSLATION to StageDecision.REUSE,
            BatchStage.LAYOUT to StageDecision.WAIT_FOR_DEPENDENCY,
        )
    }

    @Test
    fun `stale ready inpaint revision reruns inpaint and keeps layout pending`() {
        val expected = fingerprints()
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = completePage("page-1", expected).apply {
                    inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION - 1
                },
                expectedFingerprints = expected,
            ),
        )

        plan.decisions() shouldBe mapOf(
            BatchStage.DETECTION to StageDecision.REUSE,
            BatchStage.OCR to StageDecision.REUSE,
            BatchStage.INPAINT to StageDecision.RUN,
            BatchStage.TRANSLATION to StageDecision.REUSE,
            BatchStage.LAYOUT to StageDecision.WAIT_FOR_DEPENDENCY,
        )
        plan.displayReady shouldBe false
    }

    @Test
    fun `ocr change reruns ocr while preserving valid inpaint mask`() {
        val old = fingerprints()
        val current = old.copy(ocr = "ocr-new")
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = completePage("page-1", old),
                expectedFingerprints = current,
            ),
        )

        plan.decisions() shouldBe mapOf(
            BatchStage.DETECTION to StageDecision.REUSE,
            BatchStage.OCR to StageDecision.RUN,
            BatchStage.INPAINT to StageDecision.REUSE,
            BatchStage.TRANSLATION to StageDecision.WAIT_FOR_DEPENDENCY,
            BatchStage.LAYOUT to StageDecision.WAIT_FOR_DEPENDENCY,
        )
    }

    @Test
    fun `source change invalidates detection and inpaint with downstream waits`() {
        val expected = fingerprints()
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = completePage("page-1", expected).apply {
                    sourceFingerprint = "source-old"
                },
                expectedFingerprints = expected,
                sourceFingerprint = "source-new",
            ),
        )
        plan.decisions() shouldBe mapOf(
            BatchStage.DETECTION to StageDecision.RUN,
            BatchStage.OCR to StageDecision.WAIT_FOR_DEPENDENCY,
            BatchStage.INPAINT to StageDecision.WAIT_FOR_DEPENDENCY,
            BatchStage.TRANSLATION to StageDecision.WAIT_FOR_DEPENDENCY,
            BatchStage.LAYOUT to StageDecision.WAIT_FOR_DEPENDENCY,
        )
    }

    @Test
    fun `required provenance reports unknown instead of a generic mismatch`() {
        val expected = fingerprints().copy(provenanceRequired = true)
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = completePage("page-1", expected).apply {
                    detectionFingerprint = null
                },
                expectedFingerprints = expected,
            ),
        )

        plan.stage(BatchStage.DETECTION).decision shouldBe StageDecision.RUN
        plan.stage(BatchStage.DETECTION).reason shouldBe StageReasonCode.UNKNOWN_PROVENANCE
    }

    @Test
    fun `layout-only change invokes layout only`() {
        val old = fingerprints()
        val current = old.copy(layout = "layout-new")
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = completePage("page-1", old),
                expectedFingerprints = current,
            ),
        )

        plan.decisions() shouldBe mapOf(
            BatchStage.DETECTION to StageDecision.REUSE,
            BatchStage.OCR to StageDecision.REUSE,
            BatchStage.INPAINT to StageDecision.REUSE,
            BatchStage.TRANSLATION to StageDecision.REUSE,
            BatchStage.LAYOUT to StageDecision.RUN,
        )
    }

    @Test
    fun `reader ad hoc translation with current fingerprints is reused by batch`() {
        val expected = fingerprints()
        val page = completePage("page-1", expected).apply {
            translationOrigin = ArtifactOrigin.READER_ADHOC.name
        }
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = page,
                expectedFingerprints = expected,
            ),
        )

        plan.displayReady shouldBe true
        plan.stage(BatchStage.TRANSLATION).decision shouldBe StageDecision.REUSE
    }

    @Test
    fun `failed page blocks later translation even when later page is otherwise reusable`() {
        val expected = fingerprints()
        val failed = completePage("page-2", expected).apply { ocrStatus = StageStatus.FAILED }
        val plan = PageWorkPlanner.planChapter(
            listOf(
                BatchPlannerInput("page-1", completePage("page-1", expected), expectedFingerprints = expected),
                BatchPlannerInput("page-2", failed, expectedFingerprints = expected),
                BatchPlannerInput("page-3", completePage("page-3", expected), expectedFingerprints = expected),
            ),
        )

        plan.firstWorkPageKey shouldBe "page-2"
        plan.pages[1].stage(BatchStage.OCR).decision shouldBe StageDecision.FAILED
        plan.pages[2].stage(BatchStage.TRANSLATION).decision shouldBe StageDecision.WAIT_FOR_DEPENDENCY
    }

    @Test
    fun `retryable and terminal durable failures expose distinct planner decisions`() {
        val expected = fingerprints()
        val retryable = DurableFailureMetadata(
            pageKey = "page-1",
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_RETRYABLE,
            category = FailureCategory.TRANSIENT,
            retryCount = 1,
            lastFailedAtEpochMs = 100L,
            nextEligibleRetryAtEpochMs = 200L,
            failureFingerprint = expected.translation,
        )
        val future = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = PageTranslation(sourceFileName = "page-1"),
                expectedFingerprints = expected,
                durableFailure = retryable,
                nowEpochMs = 199L,
            ),
        ).stage(BatchStage.TRANSLATION)
        future.decision shouldBe StageDecision.FAILED_RETRYABLE
        future.retryEligible shouldBe false
        future.nextEligibleRetryAtEpochMs shouldBe 200L

        val due = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = PageTranslation(sourceFileName = "page-1"),
                expectedFingerprints = expected,
                durableFailure = retryable,
                nowEpochMs = 200L,
            ),
        ).stage(BatchStage.TRANSLATION)
        due.decision shouldBe StageDecision.FAILED_RETRYABLE
        due.retryEligible shouldBe true

        val terminal = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = PageTranslation(sourceFileName = "page-1"),
                expectedFingerprints = expected,
                durableFailure = retryable.copy(
                    status = ArtifactStageStatus.FAILED_TERMINAL,
                    category = FailureCategory.CONFIGURATION,
                ),
                nowEpochMs = 500L,
            ),
        ).stage(BatchStage.TRANSLATION)
        terminal.decision shouldBe StageDecision.FAILED_TERMINAL
        terminal.retryEligible shouldBe false
    }

    @Test
    fun `colorize-only page with matching source identity demands no OCR work`() {
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = colorizeOnlyPage("page-1", "sha-colored"),
                sourceFingerprint = "sha-colored",
            ),
        )

        // Identity-verified coloring is respected: no OCR rework is planned
        // (stage may be terminal or wait behind detection, but never RUN).
        plan.stage(BatchStage.OCR).decision shouldNotBe StageDecision.RUN
        plan.stage(BatchStage.TRANSLATION).decision shouldNotBe StageDecision.RUN
    }

    @Test
    fun `colorize-only page without identity evidence plans real work`() {
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = colorizeOnlyPage("page-1", "sha-colored"),
                sourceFingerprint = null,
            ),
        )

        // Identity unavailable → unverified → detection/render work is planned.
        plan.stage(BatchStage.DETECTION).decision shouldBe StageDecision.RUN
    }

    @Test
    fun `colorize-only page with stale source identity plans real work`() {
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = "page-1",
                page = colorizeOnlyPage("page-1", "sha-colored"),
                sourceFingerprint = "sha-changed",
            ),
        )

        // New page bytes invalidate the saved coloring: work is planned again.
        plan.stage(BatchStage.DETECTION).decision shouldBe StageDecision.RUN
    }

    private fun BatchPageWorkPlan.stage(stage: BatchStage): StageWorkDecision =
        stages.first { it.stage == stage }

    private fun colorizeOnlyPage(key: String, fingerprint: String) = PageTranslation(
        sourceFileName = key,
        ocrStatus = StageStatus.SKIPPED,
        translationStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
        originalImageFallback = true,
        coloredFromOriginal = true,
        coloredImageName = "$key.color.jpg",
        coloringStatus = StageStatus.READY,
        sourceFingerprint = fingerprint,
    )

    private fun BatchPageWorkPlan.decisions(): Map<BatchStage, StageDecision> =
        stages.associate { it.stage to it.decision }

    private fun fingerprints() = BatchExpectedFingerprints(
        detection = "detection",
        ocr = "ocr",
        inpaint = "inpaint",
        translation = "translation",
        layout = "layout",
    )

    private fun completePage(key: String, fingerprints: BatchExpectedFingerprints) = PageTranslation(
        sourceFileName = key,
        cleanedImageName = "$key.cleaned.jpg",
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
        detectionFingerprint = fingerprints.detection,
        ocrFingerprint = fingerprints.ocr,
        inpaintFingerprint = fingerprints.inpaint,
        translationFingerprint = fingerprints.translation,
        layoutFingerprint = fingerprints.layout,
        translationOrigin = ArtifactOrigin.BATCH.name,
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "translated",
                width = 10f,
                height = 10f,
                x = 1f,
                y = 1f,
                symHeight = 10f,
                symWidth = 10f,
                angle = 0f,
            ),
        ),
    )
}
