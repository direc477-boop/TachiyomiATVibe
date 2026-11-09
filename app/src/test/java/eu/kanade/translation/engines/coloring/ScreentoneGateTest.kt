package eu.kanade.translation.engines.coloring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pins the prototype-exact gate against the Python numeric twin on a deterministic synthetic page. */
class ScreentoneGateTest {

    /** Deterministic 96x64 synthetic: horizontal ramp 230-2x, ink lines every 6 rows, dots every 5. */
    internal fun syntheticPage(): ByteArray {
        val width = 96
        val height = 64
        val gray = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var value = 230 - 2 * x
                if (y % 6 == 0 && x > 20 && x < 76) value = 25
                if (x % 5 == 0 && y % 5 == 0) value = 60
                gray[y * width + x] = value.toByte()
            }
        }
        return gray
    }

    @Test
    fun `synthetic page scores like the numeric twin and stays below the threshold`() {
        val score = ScreentoneGate.score(syntheticPage(), 96, 64)
        assertEquals(2.43229f, score, 0.02f)
        assertTrue(score < ScreentoneGate.THRESHOLD)
    }

    @Test
    fun `flat page has no high-pass energy and scores zero`() {
        val gray = ByteArray(96 * 64) { 128.toByte() }
        assertEquals(0f, ScreentoneGate.score(gray, 96, 64))
    }
}
