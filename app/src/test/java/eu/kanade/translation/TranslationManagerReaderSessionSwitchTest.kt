package eu.kanade.translation

import android.content.Context
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.ReaderSessionIntent
import eu.kanade.translation.coordinator.ReaderTranslationCoordinator
import eu.kanade.translation.coordinator.SessionAdmission
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.coordinator.TranslationSessionCoordinator
import eu.kanade.translation.coordinator.TranslationSessionState
import eu.kanade.translation.engines.coloring.PageColorizationEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.processing.TranslationProcessor
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.File

class TranslationManagerReaderSessionSwitchTest {

    @TempDir
    lateinit var tempDir: File

    private val source = mockk<HttpSource>(relaxed = true)
    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { title } returns "fixture"
        every { source } returns 1L
    }
    private val chapter = mockk<Chapter>(relaxed = true) {
        every { id } returns 10L
        every { name } returns "Chapter 10"
        every { scanlator } returns null
    }

    @Test
    fun `colorizeChapterWithSessionSwitch cancels active reader work and admits batch`() = runBlocking<Unit> {
        val sessions = TranslationSessionCoordinator()
        val processor = mockk<TranslationProcessor>(relaxed = true)
        every { processor.colorizationEngine } returns mockk<PageColorizationEngine>(relaxed = true)
        val workerEntered = CompletableDeferred<Unit>()
        coEvery {
            processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            workerEntered.complete(Unit)
            awaitCancellation()
        }
        val queueFlow = MutableStateFlow<List<Translation>>(emptyList())
        val queuedEntry = Translation(source, manga, chapter).also {
            it.status = Translation.State.QUEUE
            it.colorizeOnly = true
        }
        val batchCoordinator = mockk<BatchTranslationCoordinator>(relaxed = true) {
            every { queueState } returns queueFlow
            every { isChapterBatchActive(any(), any()) } returns false
            every { queuedTranslationOrNull(10L) } answers {
                queueFlow.value.firstOrNull { it.chapter.id == 10L }
            }
            every { queueChapter(any(), any(), any(), any(), true) } answers {
                queueFlow.value = listOf(queuedEntry)
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store = ChapterTranslationStore.lazy(
            artifactParent = com.hippo.unifile.FakeUniFile(parent = null, backing = tempDir),
            artifactFileName = "ch10.json",
        )
        val readerCoordinator = ReaderTranslationCoordinator(
            processor = processor,
            resolveSessionStore = { _, _, _ -> store },
            admitReaderSession = { chId ->
                sessions.requestReaderSession(ReaderSessionIntent(chId)) !is SessionAdmission.Rejected
            },
            manualStreamProvider = { _, _, _, _ -> { ByteArrayInputStream(byteArrayOf(1)) } },
            sessionCoordinator = sessions,
            scope = scope,
            autoStart = true,
        )
        val manager = createManager(processor, batchCoordinator, readerCoordinator, sessions)

        try {
            readerCoordinator.translatePage(manga, chapter, source, "p1.jpg")
            withTimeout(5_000) { workerEntered.await() }
            sessions.state.value shouldBe TranslationSessionState.READER_SESSION

            val queued = withTimeout(5_000) {
                manager.colorizeChapterWithSessionSwitch(manga, chapter, autoStart = false)
            }

            queued shouldBe true
            sessions.state.value shouldBe TranslationSessionState.BATCH_SESSION
            verify(exactly = 1) { batchCoordinator.queueChapter(manga, chapter, null, false, true) }
        } finally {
            readerCoordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun `colorizeChapterWithSessionSwitch releases batch session when admission fails on idle queue`() = runBlocking<Unit> {
        val sessions = TranslationSessionCoordinator()
        val processor = mockk<TranslationProcessor>(relaxed = true)
        every { processor.colorizationEngine } returns mockk<PageColorizationEngine>(relaxed = true)
        val batchCoordinator = mockk<BatchTranslationCoordinator>(relaxed = true) {
            every { queueState } returns MutableStateFlow(emptyList())
            every { queuedTranslationOrNull(any()) } returns null
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val readerCoordinator = ReaderTranslationCoordinator(
            processor = processor,
            sessionCoordinator = sessions,
            scope = scope,
            autoStart = false,
        )
        val manager = createManager(processor, batchCoordinator, readerCoordinator, sessions)

        try {
            sessions.requestReaderSession(ReaderSessionIntent(10L))
            val queued = manager.colorizeChapterWithSessionSwitch(manga, chapter)

            queued shouldBe false
            sessions.state.value shouldBe TranslationSessionState.IDLE
            verify(exactly = 0) { batchCoordinator.startWithSessionAdmission() }
        } finally {
            readerCoordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun `colorizeChapter works directly after single-page reader translation finishes`() = runBlocking<Unit> {
        val sessions = TranslationSessionCoordinator()
        val processor = mockk<TranslationProcessor>(relaxed = true)
        every { processor.colorizationEngine } returns mockk<PageColorizationEngine>(relaxed = true)
        val processed = CompletableDeferred<Unit>()
        coEvery {
            processor.processSinglePage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            processed.complete(Unit)
            TranslationProcessor.ProcessResult.Success(
                PageTranslation(sourceFileName = "p1.jpg", translationStatus = StageStatus.READY),
            )
        }
        val queueFlow = MutableStateFlow<List<Translation>>(emptyList())
        val queuedEntry = Translation(source, manga, chapter).also {
            it.status = Translation.State.QUEUE
            it.colorizeOnly = true
        }
        val batchCoordinator = mockk<BatchTranslationCoordinator>(relaxed = true) {
            every { queueState } returns queueFlow
            every { queuedTranslationOrNull(10L) } answers {
                queueFlow.value.firstOrNull { it.chapter.id == 10L }
            }
            every { queueChapter(any(), any(), any(), any(), true) } answers {
                queueFlow.value = listOf(queuedEntry)
            }
        }
        val activeStores = ActiveChapterStoreRegistry()
        val store = ChapterTranslationStore.lazy(
            artifactParent = com.hippo.unifile.FakeUniFile(parent = null, backing = tempDir),
            artifactFileName = "ch10.json",
        )
        activeStores.register(10L, store)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val readerCoordinator = ReaderTranslationCoordinator(
            processor = processor,
            resolveSessionStore = { _, _, _ -> store },
            admitReaderSession = { chId ->
                sessions.requestReaderSession(ReaderSessionIntent(chId)) !is SessionAdmission.Rejected
            },
            manualStreamProvider = { _, _, _, _ -> { ByteArrayInputStream(byteArrayOf(1)) } },
            activeStoresProvider = { activeStores },
            sessionCoordinator = sessions,
            scope = scope,
            autoStart = true,
        )
        val manager = createManager(processor, batchCoordinator, readerCoordinator, sessions)

        try {
            readerCoordinator.translatePage(manga, chapter, source, "p1.jpg")
            withTimeout(5_000) { processed.await() }
            withTimeout(5_000) {
                while (sessions.state.value != TranslationSessionState.IDLE) {
                    kotlinx.coroutines.delay(10)
                }
            }

            manager.colorizeChapter(manga, chapter, autoStart = false) shouldBe true
            sessions.state.value shouldBe TranslationSessionState.BATCH_SESSION
        } finally {
            readerCoordinator.close()
            scope.cancel()
        }
    }

    private fun createManager(
        processor: TranslationProcessor,
        batchCoordinator: BatchTranslationCoordinator,
        readerCoordinator: ReaderTranslationCoordinator,
        sessionCoordinator: TranslationSessionCoordinator,
    ): TranslationManager {
        val colorPref = mockk<Preference<Boolean>>(relaxed = true) {
            every { get() } returns true
        }
        val prefs = mockk<tachiyomi.domain.translation.TranslationPreferences>(relaxed = true) {
            every { translationColorizationEnabled() } returns colorPref
        }
        return TranslationManager.createForTesting(
            context = mockk<Context>(relaxed = true),
            provider = mockk(relaxed = true),
            sourceManager = mockk(relaxed = true),
            translationPreferences = prefs,
            downloadProvider = mockk(relaxed = true),
            processor = processor,
            batchCoordinator = batchCoordinator,
            pendingRequestStore = mockk<TranslationPendingRequestStore>(relaxed = true),
            sessionCoordinator = sessionCoordinator,
            readerCoordinator = readerCoordinator,
        ).apply {
            colorizationEngine = mockk<PageColorizationEngine>(relaxed = true)
        }
    }
}
