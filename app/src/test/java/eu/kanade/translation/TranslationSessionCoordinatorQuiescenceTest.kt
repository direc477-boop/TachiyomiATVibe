package eu.kanade.translation

import eu.kanade.translation.coordinator.BatchSessionIntent
import eu.kanade.translation.coordinator.ReaderSessionIntent
import eu.kanade.translation.coordinator.SessionAdmission
import eu.kanade.translation.coordinator.TranslationSessionCoordinator
import eu.kanade.translation.coordinator.TranslationSessionState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TranslationSessionCoordinatorQuiescenceTest {

    @Test
    fun `joined batch switch admits reader only after real unwind`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))
        val pauseCalls = mutableListOf("none")
        val joinTimeouts = mutableListOf<Long>()
        var attempts = 0

        val admission = coordinator.switchBatchToReader(
            intent = ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
            pauseBatch = { pauseCalls += "pause" },
            joinBatch = {
                attempts += 1
                attempts == 2
            },
            timeoutMs = 3_000L,
            retryDelayMs = 0L,
            onJoinTimeout = { joinTimeouts += it },
        )

        admission shouldBe SessionAdmission.Switched(TranslationSessionState.BATCH_SESSION)
        pauseCalls shouldContainExactly listOf("none", "pause")
        joinTimeouts shouldContainExactly listOf(3_000L)
        attempts shouldBe 2
        coordinator.state.value shouldBe TranslationSessionState.READER_SESSION
    }

    @Test
    fun `timed out join keeps pausing while retry is in progress`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))
        val statesSeenByJoin = mutableListOf<TranslationSessionState>()
        var attempts = 0

        coordinator.switchBatchToReader(
            intent = ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
            pauseBatch = {},
            joinBatch = {
                statesSeenByJoin += coordinator.state.value
                attempts += 1
                attempts == 2
            },
            timeoutMs = 3_000L,
            retryDelayMs = 0L,
        )

        statesSeenByJoin shouldContainExactly listOf(
            TranslationSessionState.PAUSING,
            TranslationSessionState.PAUSING,
        )
        coordinator.state.value shouldBe TranslationSessionState.READER_SESSION
    }

    @Test
    fun `reader teardown cancellation restores batch ownership`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))

        val switchJob = launch {
            coordinator.switchBatchToReader(
                intent = ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
                pauseBatch = {},
                joinBatch = {
                    kotlinx.coroutines.awaitCancellation()
                },
                timeoutMs = 3_000L,
                retryDelayMs = 2_000L,
            )
        }
        runCurrent()
        coordinator.state.value shouldBe TranslationSessionState.PAUSING

        switchJob.cancelAndJoin()
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION
    }

    @Test
    fun `reader teardown abort prevents late reader admission`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestBatchSession(BatchSessionIntent(setOf(10L)))

        val switchJob = launch {
            coordinator.switchBatchToReader(
                intent = ReaderSessionIntent(chapterId = 10L, confirmBatchSwitch = true),
                pauseBatch = {},
                joinBatch = {
                    kotlinx.coroutines.awaitCancellation()
                },
            )
        }
        runCurrent()
        coordinator.state.value shouldBe TranslationSessionState.PAUSING

        coordinator.abortPausingToBatch() shouldBe true
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION
        switchJob.cancelAndJoin()
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION
    }

    @Test
    fun `joined reader switch retries until reader unwinds then admits batch`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestReaderSession(ReaderSessionIntent(chapterId = 10L))
        val pauseCalls = mutableListOf<String>()
        val joinTimeouts = mutableListOf<Long>()
        val statesSeenByJoin = mutableListOf<TranslationSessionState>()
        var attempts = 0

        val admission = coordinator.switchReaderToBatch(
            intent = BatchSessionIntent(setOf(10L)),
            pauseReader = { pauseCalls += "pause" },
            joinReader = {
                statesSeenByJoin += coordinator.state.value
                attempts += 1
                attempts == 2
            },
            timeoutMs = 3_000L,
            retryDelayMs = 0L,
            onJoinTimeout = { joinTimeouts += it },
        )

        admission shouldBe SessionAdmission.Switched(TranslationSessionState.READER_SESSION)
        pauseCalls shouldContainExactly listOf("pause")
        statesSeenByJoin shouldContainExactly listOf(
            TranslationSessionState.PAUSING,
            TranslationSessionState.PAUSING,
        )
        joinTimeouts shouldContainExactly listOf(3_000L)
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION
    }

    @Test
    fun `batch switch cancellation restores reader ownership`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestReaderSession(ReaderSessionIntent(chapterId = 10L))

        val switchJob = launch {
            coordinator.switchReaderToBatch(
                intent = BatchSessionIntent(setOf(10L)),
                pauseReader = {},
                joinReader = { kotlinx.coroutines.awaitCancellation() },
                timeoutMs = 3_000L,
                retryDelayMs = 2_000L,
            )
        }
        runCurrent()
        coordinator.state.value shouldBe TranslationSessionState.PAUSING

        switchJob.cancelAndJoin()
        coordinator.state.value shouldBe TranslationSessionState.READER_SESSION
    }

    @Test
    fun `abortPausingToBatch refuses to interrupt a batch-targeted switch from reader`() = runTest {
        val coordinator = TranslationSessionCoordinator()
        coordinator.requestReaderSession(ReaderSessionIntent(chapterId = 10L))
        val joinStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val allowJoin = kotlinx.coroutines.CompletableDeferred<Unit>()

        val switchJob = launch {
            val admission = coordinator.switchReaderToBatch(
                intent = BatchSessionIntent(setOf(10L)),
                pauseReader = {},
                joinReader = {
                    joinStarted.complete(Unit)
                    allowJoin.await()
                    true
                },
            )
            admission shouldBe SessionAdmission.Switched(TranslationSessionState.READER_SESSION)
        }
        joinStarted.await()
        coordinator.state.value shouldBe TranslationSessionState.PAUSING

        coordinator.abortPausingToBatch() shouldBe false
        coordinator.state.value shouldBe TranslationSessionState.PAUSING

        allowJoin.complete(Unit)
        switchJob.join()
        coordinator.state.value shouldBe TranslationSessionState.BATCH_SESSION
    }
}
