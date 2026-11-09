package eu.kanade.translation.persistence

import com.hippo.unifile.FakeUniFile
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The live COLORING marker published by colorize-only runs: visible to the
 * store's live state (holders dim, batch tracker reports the stage) while the
 * committed display stays untouched, and it never clobbers real OCR data.
 */
class ChapterTranslationStoreColorizeRunningTest {

    @TempDir
    lateinit var backing: File

    private fun newStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        artifactParent = FakeUniFile(parent = null, backing = backing),
        artifactFileName = "Chapter 1.json",
    )

    @Test
    fun `fresh page gets a RUNNING coloring entry, visible to live display`() = runTest {
        val store = newStore()

        val result = store.markColorizeRunning("p1", "test:running")

        result.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        store.state.value["p1"]?.coloringStatus shouldBe StageStatus.RUNNING
        // Display-first live store: with no committed entry yet, the running
        // marker itself is what holders observe (dim + spinner).
        store.display.value["p1"]?.coloringStatus shouldBe StageStatus.RUNNING
    }

    @Test
    fun `existing OCR data is preserved and only coloringStatus moves`() = runTest {
        val store = newStore()
        store.updatePageFromCurrentSnapshot("p1", "seed") { _ ->
            PageTranslation(sourceFileName = "p1").apply {
                ocrStatus = StageStatus.READY
            }
        }

        store.markColorizeRunning("p1", "test:running")

        val page = store.state.value.getValue("p1")
        page.ocrStatus shouldBe StageStatus.READY
        page.coloringStatus shouldBe StageStatus.RUNNING
    }

    @Test
    fun `defunct store rejects the marker`() = runTest {
        val store = newStore()
        store.markDefunct()

        val result = store.markColorizeRunning("p1", "test:running")

        result.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        store.state.value["p1"] shouldBe null
    }
}
