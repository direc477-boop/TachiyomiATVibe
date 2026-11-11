package eu.kanade.translation.persistence

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.processing.execution.TranslationStreamRegistry
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Pins the reader-facing full-chapter purge: teardown (running batch cancel,
 * queue drop, store defunct) strictly precedes file deletion; counts surface
 * in the receipt; foreign documents and other chapters survive untouched.
 */
class ChapterDataResetControllerPurgeTest {

    private val events = mutableListOf<String>()

    private fun log(event: String) {
        events += event
    }

    /** IO tree holding one chapter's translation data plus foreign documents. */
    private fun seededIo(): FakeChapterDocumentIo {
        val io = FakeChapterDocumentIo()
        AtomicChapterDocuments(io).publishJson(
            "Chapter 1.manifest.json",
            ChapterArtifactManifest(chapterKey = "Chapter 1"),
        ) shouldBe true
        io.write("Chapter 1.manifest.json.bak", byteArrayOf(1)) shouldBe true
        io.write("Chapter 1_artifacts/pages/0001/committed-g.json", byteArrayOf(2)) shouldBe true
        io.write("Chapter 1_artifacts/images/0001/gen-fp.jpg", byteArrayOf(3)) shouldBe true
        // Foreign documents the purge must never touch.
        io.write("cover.jpg", byteArrayOf(4)) shouldBe true
        io.write("Chapter 1.json.migrated", "legacy".toByteArray()) shouldBe true
        io.write("Chapter 2.manifest.json", byteArrayOf(5)) shouldBe true
        return io
    }

    /** Delegation wrapper that sequences file deletions into the shared event log. */
    private class LoggingIo(
        private val delegate: ChapterDocumentIo,
        private val log: (String) -> Unit,
    ) : ChapterDocumentIo by delegate {
        override fun delete(name: String): Boolean {
            log("ioDelete:$name")
            return delegate.delete(name)
        }
    }

    private fun newController(io: FakeChapterDocumentIo): ChapterDataResetController {
        val translator = mockk<BatchTranslationCoordinator>(relaxed = true)
        coEvery { translator.cancelTranslatorJobAndJoin(any()) } answers { log("cancelTranslatorJobAndJoin") }
        val store = mockk<ChapterTranslationStore>(relaxed = true)
        every { store.state } returns MutableStateFlow(emptyMap())
        coEvery { store.markDefunct() } coAnswers { log("markDefunct") }
        val streamRegistry = mockk<TranslationStreamRegistry>(relaxed = true)
        every { streamRegistry.clearChapter(any(), any(), any()) } answers { log("clearChapter") }
        val provider = mockk<TranslationFileProvider>(relaxed = true)
        val sourceManager = mockk<tachiyomi.domain.source.service.SourceManager>(relaxed = true)
        val durableResolver = DurableChapterStatusResolver(
            providerProvider = { provider },
            sourceManagerProvider = { sourceManager },
            activeStoresProvider = { ActiveChapterStoreRegistry() },
        )
        return ChapterDataResetController(
            findTranslationDocumentFn = { _, _, _, _ ->
                log("findTranslationDocument")
                TranslationDocument(mockk<UniFile>(), "Chapter 1.json")
            },
            cancelPageTranslationsFn = { log("cancelPageTranslations") },
            cancelPageTranslationFn = { _, _ -> true },
            removeFromTranslationQueueFn = { log("removeFromTranslationQueue") },
            translatorProvider = { translator },
            disposeBatchTrackerFn = { log("disposeBatchTracker") },
            unregisterActiveTranslationStoreFn = {
                log("unregisterActiveTranslationStore")
                store.markDefunct()
            },
            streamRegistryProvider = { streamRegistry },
            providerProvider = { provider },
            retireChapterCompanionImagesFn = { _, _, _ ->
                log("retireChapterCompanionImages")
                3
            },
            retirePageCompanionImageFn = { _, _, _, _, _, _ -> },
            durableStatusResolverProvider = { durableResolver },
            activeStoresProvider = { ActiveChapterStoreRegistry() },
            openExistingChapterTranslationStoreFn = { _, _, _, _, _ -> null },
            chapterIoFactory = { LoggingIo(io, ::log) },
        )
    }

    private fun chapter() = mockk<Chapter>(relaxed = true) {
        every { id } returns 42L
        every { name } returns "Chapter 1"
        every { scanlator } returns null
    }

    @Test
    fun `purge cancels the running batch before deleting and reports counts`() = runBlocking<Unit> {
        val io = seededIo()
        val controller = newController(io)
        events.clear()

        val receipt = controller.deleteTranslation(
            chapter = chapter(),
            manga = mockk<Manga>(relaxed = true),
            source = mockk<Source>(relaxed = true),
        )

        assertTrue(receipt.completed, "purge over a clean tree must complete: ${receipt.failures}")
        // 2 authority files + 2 artifact payloads + 3 companion images.
        assertEquals(7, receipt.removedFiles, "receipt must report every removed file")
        // artifact root + pages + 0001 + images + 0001-under-images.
        assertEquals(5, receipt.removedDirs, "receipt must report every removed directory")
        assertFalse(io.exists("Chapter 1.manifest.json"))
        assertFalse(io.exists("Chapter 1.manifest.json.bak"))
        assertFalse(io.exists("Chapter 1_artifacts"))
        // Foreign and other-chapter documents survive.
        assertTrue(io.exists("cover.jpg"))
        assertTrue(io.exists("Chapter 1.json.migrated"))
        assertTrue(io.exists("Chapter 2.manifest.json"))

        val firstDelete = events.indexOfFirst { it.startsWith("ioDelete:") }
        assertTrue(firstDelete >= 0, "the purge must have deleted files")
        // Running-batch guard: cancel and queue-drop precede ANY file deletion.
        assertTrue(
            events.indexOf("cancelPageTranslations") in 0 until firstDelete,
            "page work must be cancelled before deletion: $events",
        )
        assertTrue(
            events.indexOf("removeFromTranslationQueue") in 0 until firstDelete,
            "the queue entry must drop before deletion: $events",
        )
        assertTrue(
            events.indexOf("cancelTranslatorJobAndJoin") in 0 until firstDelete,
            "the running batch worker must be cancelled and joined before deletion: $events",
        )
        assertTrue(
            events.indexOf("markDefunct") in 0 until firstDelete,
            "the live store must be defunct before deletion: $events",
        )
        assertTrue(
            events.indexOf("clearChapter") in 0 until firstDelete,
            "reader display streams must be evicted before deletion: $events",
        )
        assertEquals(
            listOf(
                "findTranslationDocument",
                "cancelPageTranslations",
                "removeFromTranslationQueue",
                "cancelTranslatorJobAndJoin",
                "disposeBatchTracker",
                "unregisterActiveTranslationStore",
                "markDefunct",
                "clearChapter",
            ),
            events.take(8),
            "teardown sequence changed: $events",
        )
        assertTrue(
            events.last { it.startsWith("ioDelete:") }.let { events.indexOf("retireChapterCompanionImages") > events.indexOf(it) },
            "companion-image retirement must follow artifact deletion: $events",
        )
    }

    @Test
    fun `a manifest removal failure retains the tree and reports typed incompleteness`() = runBlocking<Unit> {
        val io = seededIo().apply { deleteNamesToFail += "Chapter 1.manifest.json" }
        val controller = newController(io)
        events.clear()

        val receipt = controller.deleteTranslation(
            chapter = chapter(),
            manga = mockk<Manga>(relaxed = true),
            source = mockk<Source>(relaxed = true),
        )

        assertFalse(receipt.completed, "a failed authority removal must never read as a clean reset")
        assertTrue(
            receipt.failures.any { it.contains("Chapter 1.manifest.json") },
            "the failed authority name must surface in the receipt: ${receipt.failures}",
        )
        // The owned tree is retained for retry; nothing was deleted.
        assertTrue(io.exists("Chapter 1.manifest.json"))
        assertTrue(io.exists("Chapter 1_artifacts"))
        assertEquals(0, receipt.removedFiles)
        assertEquals(0, receipt.removedDirs)
    }
}
