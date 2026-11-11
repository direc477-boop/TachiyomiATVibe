package eu.kanade.translation.persistence

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pins F2 parity: the header-only manifest probe must project the SAME
 * durable chapter status as the full hydration for every fixture class the
 * manga badge cares about — completed, mid-run, paused, errored, missing,
 * and future-schema. The probe never opens a page snapshot; committed-terminal
 * evidence comes from the manifest records themselves.
 */
class ProbeStatusProjectionParityTest {

    @TempDir
    lateinit var mangaDir: File

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun manifestFile(): File = File(mangaDir, "Chapter 1.manifest.json")

    private fun displayablePage() = PageTranslation(
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "hello",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "page.cleaned.abc.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    /** Materializes one committed page through the real write path. */
    private suspend fun writeCommittedChapter(trustedCount: Boolean) {
        val store = ChapterStoreOpener.openArtifactSuspend(root(), "Chapter 1.json")
        store.updatePage("page.jpg") { displayablePage() }
        store.closeAndFlush()
        if (trustedCount) {
            val written = ArtifactDocumentJson.decodeFromString<ChapterArtifactManifest>(manifestFile().readText())
            manifestFile().writeText(
                ArtifactDocumentJson.encodeToString(
                    ChapterArtifactManifest.serializer(),
                    written.copy(expectedPageCount = 1, expectedPageCountTrusted = true),
                ),
            )
        }
    }

    private fun writeRawManifest(manifest: ChapterArtifactManifest) {
        manifestFile().writeText(
            ArtifactDocumentJson.encodeToString(ChapterArtifactManifest.serializer(), manifest),
        )
    }

    private suspend fun hydratedStatus(): Translation.State? {
        return when (val read = ChapterStoreOpener.readDurableChapter(root(), "Chapter 1.json")) {
            is DurableChapterRead.Supported ->
                ChapterStatusProjection.artifactStatus(read.state.statusProjectionData())
            else -> null
        }
    }

    private fun probedStatus(): Translation.State? {
        val probe = ChapterStoreOpener.probeArtifactManifest(root(), "Chapter 1.json")
        if (!probe.exists) return null
        val manifest = probe.manifest ?: return null
        if (manifest.schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION) return null
        if (manifest.schemaVersion < ChapterArtifactManifest.MIN_SUPPORTED_SCHEMA_VERSION) return null
        return ChapterStatusProjection.artifactStatus(ChapterStoreOpener.probeStatusProjectionData(manifest))
    }

    @Test
    fun `committed chapter with untrusted count projects READY_WITH_WARNINGS on both paths`() = runTest {
        writeCommittedChapter(trustedCount = false)

        val hydrated = hydratedStatus()
        val probed = probedStatus()

        hydrated shouldBe Translation.State.READY_WITH_WARNINGS
        probed shouldBe hydrated
    }

    @Test
    fun `committed chapter with trusted count projects TRANSLATED on both paths`() = runTest {
        writeCommittedChapter(trustedCount = true)

        val hydrated = hydratedStatus()
        val probed = probedStatus()

        hydrated shouldBe Translation.State.TRANSLATED
        probed shouldBe hydrated
    }

    @Test
    fun `retryable durable failure projects PAUSED on both paths`() = runTest {
        writeRawManifest(
            ChapterArtifactManifest(
                chapterKey = "Chapter 1",
                durableFailures = mapOf(
                    "p0:TRANSLATION" to DurableFailureMetadata(
                        pageKey = "p0",
                        stage = ArtifactStage.TRANSLATION,
                        status = ArtifactStageStatus.FAILED_RETRYABLE,
                        category = FailureCategory.TRANSIENT,
                        retryCount = 1,
                        lastFailureMessage = "translation provider failed",
                        lastFailedAtEpochMs = 1_000L,
                        nextEligibleRetryAtEpochMs = 2_000L,
                    ),
                ),
            ),
        )

        val hydrated = hydratedStatus()
        val probed = probedStatus()

        hydrated shouldBe Translation.State.PAUSED
        probed shouldBe hydrated
    }

    @Test
    fun `terminal durable failure projects ERROR on both paths`() = runTest {
        writeRawManifest(
            ChapterArtifactManifest(
                chapterKey = "Chapter 1",
                durableFailures = mapOf(
                    "p0:TRANSLATION" to DurableFailureMetadata(
                        pageKey = "p0",
                        stage = ArtifactStage.TRANSLATION,
                        status = ArtifactStageStatus.FAILED_TERMINAL,
                        category = FailureCategory.CONFIGURATION,
                        retryCount = 3,
                        lastFailureMessage = "unrecoverable",
                        lastFailedAtEpochMs = 1_000L,
                        nextEligibleRetryAtEpochMs = null,
                    ),
                ),
            ),
        )

        val hydrated = hydratedStatus()
        val probed = probedStatus()

        hydrated shouldBe Translation.State.ERROR
        probed shouldBe hydrated
    }

    @Test
    fun `missing manifest resolves null on both paths`() = runTest {
        hydratedStatus() shouldBe null
        probedStatus() shouldBe null
    }

    @Test
    fun `future schema resolves null on both paths and preserves bytes`() = runTest {
        val future = ChapterArtifactManifest(
            schemaVersion = 99,
            chapterKey = "Chapter 1",
            pages = mapOf("page.jpg" to PageArtifactRecord(pageKey = "page.jpg")),
        )
        writeRawManifest(future)
        val bytes = manifestFile().readText()

        hydratedStatus() shouldBe null
        probedStatus() shouldBe null

        manifestFile().readText() shouldBe bytes
    }

    @Test
    fun `candidate in flight projects READY_WITH_WARNINGS on both paths`() = runTest {
        writeCommittedChapter(trustedCount = false)
        val written = ArtifactDocumentJson.decodeFromString<ChapterArtifactManifest>(manifestFile().readText())
        val record = written.pages.getValue("page.jpg")
        writeRawManifest(
            written.copy(
                pages = written.pages + (
                    "page2.jpg" to record.copy(
                        pageKey = "page2.jpg",
                        candidate = CandidateGenerationMetadata(generationId = "g2"),
                    )
                    ),
            ),
        )

        val hydrated = hydratedStatus()
        val probed = probedStatus()

        hydrated shouldBe Translation.State.READY_WITH_WARNINGS
        probed shouldBe hydrated
    }

    @Test
    fun `colorize-only committed record projects terminal on both paths`() = runTest {
        writeRawManifest(
            ChapterArtifactManifest(
                chapterKey = "Chapter 1",
                expectedPageCount = 1,
                expectedPageCountTrusted = true,
                pages = mapOf(
                    "page.jpg" to PageArtifactRecord(
                        pageKey = "page.jpg",
                        committed = CommittedBundleMetadata(
                            generationId = "g1",
                            displayBase = DisplayBaseReference(
                                kind = DisplayBaseKind.ORIGINAL_SOURCE,
                                validated = true,
                            ),
                        ),
                        displayState = eu.kanade.translation.model.PageDisplayState.COLORIZE_ONLY_COMPLETE,
                    ),
                ),
            ),
        )

        val hydrated = hydratedStatus()
        val probed = probedStatus()

        hydrated shouldBe Translation.State.TRANSLATED
        probed shouldBe hydrated
    }
}
