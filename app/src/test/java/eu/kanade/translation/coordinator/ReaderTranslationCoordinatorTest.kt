package eu.kanade.translation.coordinator

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.processing.TranslationProcessor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream

class ReaderTranslationCoordinatorTest {

    private val processor: TranslationProcessor = mockk(relaxed = true)
    private val manga = mockk<Manga>(relaxed = true)
    private val chapter = mockk<Chapter>(relaxed = true) { every { id } returns 1L }
    private val source = mockk<HttpSource>(relaxed = true)
    private var isBatchActive = false

    private var testScope: CoroutineScope? = null
    private var coordinator: ReaderTranslationCoordinator? = null

    private fun createCoordinator(autoStart: Boolean = false): ReaderTranslationCoordinator {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        testScope = scope
        return ReaderTranslationCoordinator(
            processor = processor,
            isBatchActiveForChapter = { isBatchActive },
            scope = scope,
            autoStart = autoStart,
        ).also { coordinator = it }
    }

    @AfterEach
    fun tearDown() {
        coordinator?.close()
        testScope?.cancel()
    }

    @Test
    fun `enqueuePage enqueues manual request and preempts auto`() = runTest {
        val coord = createCoordinator()
        coord.setAutoEnabled("ch1", true)
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }

        val autoReq = ReaderTranslationCoordinator.PageRequest(
            session = TranslationSession("session", manga, chapter, source, store),
            chapterKey = "ch1",
            pageKey = "p1.jpg",
            isManual = false,
            streamFn = null,
        )
        val manualReq = ReaderTranslationCoordinator.PageRequest(
            session = TranslationSession("session", manga, chapter, source, store),
            chapterKey = "ch1",
            pageKey = "p2.jpg",
            isManual = true,
            streamFn = null,
        )

        coord.enqueuePage(autoReq) shouldBe true
        coord.enqueuePage(manualReq) shouldBe true
        coord.pendingCount.value shouldBe 2
    }

    @Test
    fun `enqueuePage rejects duplicate in-flight page`() = runTest {
        val coord = createCoordinator()
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }

        val req = ReaderTranslationCoordinator.PageRequest(
            session = TranslationSession("session", manga, chapter, source, store),
            chapterKey = "ch1",
            pageKey = "p1.jpg",
            isManual = true,
            streamFn = null,
        )

        coord.enqueuePage(req) shouldBe true
        coord.enqueuePage(req) shouldBe false
    }

    @Test
    fun `updateViewportWindow trims distant auto pages`() = runTest {
        val coord = createCoordinator()
        coord.setAutoEnabled("ch1", true)
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }

        val req1 = ReaderTranslationCoordinator.PageRequest(
            session = TranslationSession("session", manga, chapter, source, store),
            chapterKey = "ch1",
            pageKey = "p1.jpg",
            isManual = false,
            streamFn = null,
        )
        val req2 = ReaderTranslationCoordinator.PageRequest(
            session = TranslationSession("session", manga, chapter, source, store),
            chapterKey = "ch1",
            pageKey = "p2.jpg",
            isManual = false,
            streamFn = null,
        )

        coord.enqueuePage(req1)
        coord.enqueuePage(req2)
        coord.pendingCount.value shouldBe 2

        coord.updateViewportWindow("ch1", setOf("p1.jpg"))
        coord.pendingCount.value shouldBe 1
    }

    @Test
    fun `coordinator yields when batch is active`() = runTest {
        isBatchActive = true
        val coord = createCoordinator(autoStart = true)
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }

        val req = ReaderTranslationCoordinator.PageRequest(
            session = TranslationSession("session", manga, chapter, source, store),
            chapterKey = "ch1",
            pageKey = "p1.jpg",
            isManual = true,
            streamFn = null,
        )

        coord.enqueuePage(req)

        coVerify(exactly = 0) { processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `idle session releases after manual page completes while preserving activeStores`(@org.junit.jupiter.api.io.TempDir tempDir: java.io.File) = runBlocking {
        val sessions = TranslationSessionCoordinator()
        val activeStores = ActiveChapterStoreRegistry()
        val store = ChapterTranslationStore.lazy(
            artifactParent = com.hippo.unifile.FakeUniFile(parent = null, backing = tempDir),
            artifactFileName = "ch1.json",
        )
        activeStores.register(1L, store)
        val processed = CompletableDeferred<Unit>()
        coEvery {
            processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            processed.complete(Unit)
            TranslationProcessor.ProcessResult.Success(
                PageTranslation(sourceFileName = "p1.jpg", translationStatus = StageStatus.READY),
            )
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        testScope = scope
        val coord = ReaderTranslationCoordinator(
            processor = processor,
            resolveSessionStore = { _, _, _ -> store },
            admitReaderSession = { chId ->
                sessions.requestReaderSession(ReaderSessionIntent(chId)) !is SessionAdmission.Rejected
            },
            manualStreamProvider = { _, _, _, _ -> { ByteArrayInputStream(byteArrayOf(1)) } },
            isAutoTranslateEnabled = { false },
            activeStoresProvider = { activeStores },
            sessionCoordinator = sessions,
            scope = scope,
            autoStart = true,
        ).also { coordinator = it }

        coord.translatePage(manga, chapter, source, "p1.jpg")
        withTimeout(5_000) { processed.await() }
        withTimeout(5_000) {
            while (sessions.state.value != TranslationSessionState.IDLE) {
                kotlinx.coroutines.delay(10)
            }
        }

        sessions.state.value shouldBe TranslationSessionState.IDLE
        coord.sessionForChapter("1") shouldBe null
        activeStores.get(1L) shouldBe store
    }

    @Test
    fun `toggling auto translation off releases reader session when idle`() = runBlocking {
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        testScope = scope
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }
        var autoEnabled = true
        val coord = ReaderTranslationCoordinator(
            processor = processor,
            admitReaderSession = { chId ->
                sessions.requestReaderSession(ReaderSessionIntent(chId)) !is SessionAdmission.Rejected
            },
            isAutoTranslateEnabled = { autoEnabled },
            sessionCoordinator = sessions,
            scope = scope,
            autoStart = false,
        ).also { coordinator = it }

        val token = coord.bindSession(
            TranslationSession("session", manga, chapter, source, store),
            TranslatorComputeClass.REMOTE_IO,
        )
        token shouldNotBe null
        sessions.state.value shouldBe TranslationSessionState.READER_SESSION

        coord.setAutoEnabled("1", true)
        autoEnabled = false
        coord.setAutoEnabled("1", false)

        withTimeout(5_000) {
            while (sessions.state.value != TranslationSessionState.IDLE) {
                kotlinx.coroutines.delay(10)
            }
        }
        sessions.state.value shouldBe TranslationSessionState.IDLE
    }

    @Test
    fun `failed preparation releases reader session when queue stays empty`() = runBlocking {
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        testScope = scope
        val coord = ReaderTranslationCoordinator(
            processor = processor,
            sessionCoordinator = sessions,
            scope = scope,
            autoStart = false,
        ).also { coordinator = it }

        coord.beginSessionPreparation()
        sessions.requestReaderSession(ReaderSessionIntent(1L))
        sessions.state.value shouldBe TranslationSessionState.READER_SESSION

        coord.endSessionPreparationAndReleaseIfIdle()

        sessions.state.value shouldBe TranslationSessionState.IDLE
    }

    @Test
    fun `updateViewport enqueues and processes the current page first`(@org.junit.jupiter.api.io.TempDir tempDir: java.io.File) = runBlocking<Unit> {
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        testScope = scope
        val store = ChapterTranslationStore.lazy(
            artifactParent = com.hippo.unifile.FakeUniFile(parent = null, backing = tempDir),
            artifactFileName = "ch1.json",
        )
        val processedOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
        val allProcessed = CompletableDeferred<Unit>()
        coEvery {
            processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val pageKey = firstArg<String>()
            processedOrder.add(pageKey)
            if (processedOrder.size >= 2) allProcessed.complete(Unit)
            TranslationProcessor.ProcessResult.Success(
                PageTranslation(sourceFileName = pageKey, translationStatus = StageStatus.READY),
            )
        }
        val coord = ReaderTranslationCoordinator(
            processor = processor,
            resolveSessionStore = { _, _, _ -> store },
            admitReaderSession = { chId ->
                sessions.requestReaderSession(ReaderSessionIntent(chId)) !is SessionAdmission.Rejected
            },
            manualStreamProvider = { _, _, _, _ -> { ByteArrayInputStream(byteArrayOf(1)) } },
            isAutoTranslateEnabled = { true },
            sessionCoordinator = sessions,
            scope = scope,
            autoStart = true,
        ).also { coordinator = it }

        // Simulate the toggle: bind the queue session, arm auto, dispatch the
        // viewport sitting on page index 2 of a 5-page chapter.
        val session = coord.openSession(manga, chapter, source)!!
        val token = coord.bindSession(session, TranslatorComputeClass.REMOTE_IO)!!
        token shouldNotBe null
        coord.setAutoEnabled("1", true)
        coord.updateViewport(
            token = token,
            pageIndex = 2,
            pageCount = 5,
        ) { idx ->
            ReaderPageWork(
                pageKey = "p$idx.jpg",
                readerPageIndex = idx,
                streamFn = { ByteArrayInputStream(byteArrayOf(1)) },
            )
        }

        withTimeout(10_000) { allProcessed.await() }
        withTimeout(10_000) {
            while (store.state.value.size < 2) {
                kotlinx.coroutines.delay(10)
            }
        }
        processedOrder.first() shouldBe "p2.jpg"
        // Flush the write drain before @TempDir cleanup; the background
        // writer otherwise keeps the artifact locked past the test.
        store.closeAndFlush()
    }

    @Test
    fun `manual current-page kick survives viewport supersession and processes first`(@org.junit.jupiter.api.io.TempDir tempDir: java.io.File) = runBlocking<Unit> {
        val sessions = TranslationSessionCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        testScope = scope
        val store = ChapterTranslationStore.lazy(
            artifactParent = com.hippo.unifile.FakeUniFile(parent = null, backing = tempDir),
            artifactFileName = "ch1.json",
        )
        val processedOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
        val releaseFirstPage = CompletableDeferred<Unit>()
        coEvery {
            processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val pageKey = firstArg<String>()
            // Hold the first (manual) page so the viewport dispatch below runs
            // while it is still in flight — the supersession race.
            if (pageKey == "p2.jpg") releaseFirstPage.await()
            processedOrder.add(pageKey)
            TranslationProcessor.ProcessResult.Success(
                PageTranslation(sourceFileName = pageKey, translationStatus = StageStatus.READY),
            )
        }
        val coord = ReaderTranslationCoordinator(
            processor = processor,
            resolveSessionStore = { _, _, _ -> store },
            admitReaderSession = { chId ->
                sessions.requestReaderSession(ReaderSessionIntent(chId)) !is SessionAdmission.Rejected
            },
            manualStreamProvider = { _, _, _, _ -> { ByteArrayInputStream(byteArrayOf(1)) } },
            isAutoTranslateEnabled = { true },
            sessionCoordinator = sessions,
            scope = scope,
            autoStart = true,
        ).also { coordinator = it }

        val session = coord.openSession(manga, chapter, source)!!
        val token = coord.bindSession(session, TranslatorComputeClass.REMOTE_IO)!!
        coord.setAutoEnabled("1", true)

        // The toggle fix: the initiating page is submitted on the manual lane.
        coord.submitPage(
            token,
            ReaderPageWork("p2.jpg", readerPageIndex = 2, streamFn = { ByteArrayInputStream(byteArrayOf(1)) }),
            ReaderTrigger.MANUAL_TAP,
        )
        // A quick swipe lands a newer viewport dispatch (window 3..4) while the
        // kicked page is queued or in flight.
        coord.updateViewport(
            token = token,
            pageIndex = 3,
            pageCount = 5,
        ) { idx ->
            ReaderPageWork(
                pageKey = "p$idx.jpg",
                readerPageIndex = idx,
                streamFn = { ByteArrayInputStream(byteArrayOf(1)) },
            )
        }

        withTimeout(10_000) { releaseFirstPage.complete(Unit) }
        withTimeout(10_000) {
            while (processedOrder.size < 3) {
                kotlinx.coroutines.delay(10)
            }
        }
        withTimeout(10_000) {
            while (store.state.value.size < 3) {
                kotlinx.coroutines.delay(10)
            }
        }
        processedOrder.first() shouldBe "p2.jpg"
        processedOrder shouldBe listOf("p2.jpg", "p3.jpg", "p4.jpg")
        // Flush the write drain before @TempDir cleanup; the background
        // writer otherwise keeps the artifact locked past the test.
        store.closeAndFlush()
    }

    @Test
    fun `switchToBatchSession cancels active reader work and admits batch ownership`(@org.junit.jupiter.api.io.TempDir tempDir: java.io.File) = runBlocking {
        val sessions = TranslationSessionCoordinator()
        val store = ChapterTranslationStore.lazy(
            artifactParent = com.hippo.unifile.FakeUniFile(parent = null, backing = tempDir),
            artifactFileName = "ch1.json",
        )
        val workerEntered = CompletableDeferred<Unit>()
        coEvery {
            processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            workerEntered.complete(Unit)
            awaitCancellation()
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        testScope = scope
        val coord = ReaderTranslationCoordinator(
            processor = processor,
            resolveSessionStore = { _, _, _ -> store },
            admitReaderSession = { chId ->
                sessions.requestReaderSession(ReaderSessionIntent(chId)) !is SessionAdmission.Rejected
            },
            manualStreamProvider = { _, _, _, _ -> { ByteArrayInputStream(byteArrayOf(1)) } },
            sessionCoordinator = sessions,
            scope = scope,
            autoStart = true,
        ).also { coordinator = it }

        coord.translatePage(manga, chapter, source, "p1.jpg")
        withTimeout(5_000) { workerEntered.await() }
        sessions.state.value shouldBe TranslationSessionState.READER_SESSION

        val admission = withTimeout(5_000) { coord.switchToBatchSession(setOf(1L)) }

        admission shouldBe SessionAdmission.Switched(TranslationSessionState.READER_SESSION)
        sessions.state.value shouldBe TranslationSessionState.BATCH_SESSION
    }
}
