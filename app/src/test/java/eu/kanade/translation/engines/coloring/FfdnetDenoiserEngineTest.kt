package eu.kanade.translation.engines.coloring

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pins FfdnetDenoiserEngine lifecycle, odd-dimension padding/slicing, and pixel normalization.
 */
class FfdnetDenoiserEngineTest {

    private class FakeSession(
        val onRun: (input: FloatArray, evenHeight: Int, evenWidth: Int) -> FloatArray = { input, h, w ->
            // Default echo of R-plane
            val rPlane = FloatArray(h * w)
            System.arraycopy(input, 0, rPlane, 0, h * w)
            rPlane
        },
    ) : FfdnetDenoiserEngine.Dependencies.Session {
        var closed = false
        var runCount = 0

        override fun run(input: FloatArray, evenHeight: Int, evenWidth: Int): FloatArray {
            runCount++
            return onRun(input, evenHeight, evenWidth)
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `engine is available when model file exists and session opens`(@TempDir tempDir: File) {
        val modelFile = File(tempDir, "ffdnet_fp16.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val session = FakeSession()
        val engine = FfdnetDenoiserEngine(
            FfdnetDenoiserEngine.Dependencies(
                resolveModel = { modelFile },
                openSession = { session },
            ),
        )

        assertTrue(engine.isAvailable())
        assertEquals(null, engine.initializationError())
    }

    @Test
    fun `engine is unavailable when model resolution throws`() {
        val engine = FfdnetDenoiserEngine(
            FfdnetDenoiserEngine.Dependencies(
                resolveModel = { throw IllegalStateException("Model asset missing") },
                openSession = { FakeSession() },
            ),
        )

        assertFalse(engine.isAvailable())
        engine.initializationError() shouldContain "Model asset missing"
    }

    @Test
    fun `close releases session and resets availability state`(@TempDir tempDir: File) {
        val modelFile = File(tempDir, "ffdnet_fp16.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var sessionCreated = 0
        var lastSession: FakeSession? = null
        val engine = FfdnetDenoiserEngine(
            FfdnetDenoiserEngine.Dependencies(
                resolveModel = { modelFile },
                openSession = {
                    sessionCreated++
                    FakeSession().also { lastSession = it }
                },
            ),
        )

        assertTrue(engine.isAvailable())
        assertEquals(1, sessionCreated)
        assertFalse(lastSession!!.closed)

        engine.close()
        assertTrue(lastSession!!.closed)

        // Next availability call rebuilds session
        assertTrue(engine.isAvailable())
        assertEquals(2, sessionCreated)
    }

    @Test
    fun `denoisePixels pads odd dimensions to even and slices output back to native`() {
        // 3x3 input image
        val width = 3
        val height = 3
        val pixels = IntArray(9) { i ->
            val v = (i * 20) and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        var passedH = 0
        var passedW = 0
        var recordedInput: FloatArray? = null

        val session = FakeSession(
            onRun = { input, h, w ->
                passedH = h
                passedW = w
                recordedInput = input.copyOf()
                // Return inverted plane
                FloatArray(h * w) { idx -> 1f - input[idx] }
            },
        )

        val engine = FfdnetDenoiserEngine(
            FfdnetDenoiserEngine.Dependencies(
                resolveModel = { File("unused") },
                openSession = { session },
            ),
        )

        val output = engine.denoisePixels(session, pixels, width, height)

        assertEquals(4, passedH)
        assertEquals(4, passedW)
        assertEquals(3 * 16, recordedInput!!.size) // 3 channels * 4 * 4

        // Check horizontal padding for row 0: col 3 must replicate col 2
        assertEquals(recordedInput!![0 * 4 + 2], recordedInput!![0 * 4 + 3])
        // Check vertical padding: row 3 must replicate row 2
        for (x in 0 until 4) {
            assertEquals(recordedInput!![2 * 4 + x], recordedInput!![3 * 4 + x])
        }

        // Output must be cropped back to 3x3 = 9 bytes
        assertEquals(9, output.size)
        // Check pixel values (inverted)
        for (i in 0 until 9) {
            val originalVal = (i * 20) / 255f
            val expectedVal = ((1f - originalVal) * 255f + 0.5f).toInt()
            assertEquals(expectedVal, output[i].toInt() and 0xFF)
        }
    }

    @Test
    fun `denoisePixels clamps out of range values to 0 and 255`() {
        val width = 2
        val height = 2
        val pixels = IntArray(4) { 0xFF000000.toInt() }

        val session = FakeSession(
            onRun = { _, h, w ->
                floatArrayOf(-0.5f, 0.0f, 1.0f, 1.5f)
            },
        )

        val engine = FfdnetDenoiserEngine(
            FfdnetDenoiserEngine.Dependencies(
                resolveModel = { File("unused") },
                openSession = { session },
            ),
        )

        val output = engine.denoisePixels(session, pixels, width, height)
        assertEquals(0, output[0].toInt() and 0xFF)
        assertEquals(0, output[1].toInt() and 0xFF)
        assertEquals(255, output[2].toInt() and 0xFF)
        assertEquals(255, output[3].toInt() and 0xFF)
    }
}
