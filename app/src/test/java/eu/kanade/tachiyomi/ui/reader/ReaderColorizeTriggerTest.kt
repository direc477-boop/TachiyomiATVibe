package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Truth table for the reader colorize trigger: gates run in order and
 * admission never fires behind a failed gate (asserted via the admit counter).
 * Downloaded-ness is NOT a gate: streamed chapters colorize from their source
 * pages.
 */
class ReaderColorizeTriggerTest {

    @Test
    fun `unavailable wins when colorization is not ready and skips admission`() {
        var admissions = 0
        val outcome = resolve(ready = false) {
            admissions++
            true
        }

        outcome shouldBe ReaderColorizeOutcome.UNAVAILABLE
        admissions shouldBe 0
    }

    @Test
    fun `ready chapter queues on successful admission`() {
        val outcome = resolve(ready = true) { true }

        outcome shouldBe ReaderColorizeOutcome.QUEUED
    }

    @Test
    fun `refused admission reports queue failure`() {
        val outcome = resolve(ready = true) { false }

        outcome shouldBe ReaderColorizeOutcome.QUEUE_FAILED
    }

    @Test
    fun `active translation job wins over admission and skips admission`() {
        var admissions = 0
        val outcome = resolve(ready = true, translationJobActive = true) {
            admissions++
            true
        }

        // The queued translation job is reported as-is, never redirected.
        outcome shouldBe ReaderColorizeOutcome.TRANSLATION_JOB_ACTIVE
        admissions shouldBe 0
    }

    @Test
    fun `unavailable wins over an active translation job`() {
        var admissions = 0
        val outcome = resolve(ready = false, translationJobActive = true) {
            admissions++
            true
        }

        outcome shouldBe ReaderColorizeOutcome.UNAVAILABLE
        admissions shouldBe 0
    }

    private fun resolve(
        ready: Boolean,
        translationJobActive: Boolean = false,
        admit: () -> Boolean,
    ) = resolveReaderColorizeOutcome(ready, translationJobActive, admit)
}
