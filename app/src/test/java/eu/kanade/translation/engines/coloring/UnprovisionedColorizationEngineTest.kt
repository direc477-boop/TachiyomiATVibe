package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A selected-but-unprovisioned NPU variant must fail loudly through the engine
 * contract — never available, never stamped, and never returning a colorized
 * bitmap from a different artifact than the user picked.
 */
class UnprovisionedColorizationEngineTest {

    @Test
    fun `engine reports unavailable with the provision reason`() {
        val engine = UnprovisionedColorizationEngine("W8A8 artifact not provisioned")

        engine.isAvailable() shouldBe false
        engine.currentStamp() shouldBe null
        engine.initializationError() shouldBe "W8A8 artifact not provisioned"
    }

    @Test
    fun `colorize fails visibly with the provision reason`() {
        val engine = UnprovisionedColorizationEngine("W8A8 artifact not provisioned")

        val failure = assertThrows<IllegalStateException> {
            engine.colorize(source = mockk<Bitmap>(), seed = 0L)
        }
        failure.message shouldContain "W8A8 artifact not provisioned"
    }
}
