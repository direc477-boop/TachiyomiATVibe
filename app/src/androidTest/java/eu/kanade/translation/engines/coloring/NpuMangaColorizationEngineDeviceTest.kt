package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NpuMangaColorizationEngineDeviceTest {

    @Test
    fun npuModelInitializesAndColorsSyntheticPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NpuMangaColorizationEngine(context)
        val source = Bitmap.createBitmap(576, 832, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(576 * 832) { index ->
            val x = index % 576
            val y = index / 576
            val gray = ((x + y) / 6).coerceIn(20, 240)
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        source.setPixels(pixels, 0, 576, 0, 0, 576, 832)

        try {
            val available = engine.isAvailable()
            val error = engine.initializationError()
            assertTrue("MCV2 NPU engine failed to initialize: $error", available)

            val stamp = engine.currentStamp()
            assertNotNull("Stamp should not be null", stamp)
            assertTrue(
                "Stamp $stamp must contain expected recipe SHA256",
                stamp!!.contains("d60e18b2c64a11c25256969b24532747c06d9cad10327ec68281cd1ac0e94b83"),
            )

            val output = engine.colorize(source, 0L)
            try {
                assertEquals(576, output.width)
                assertEquals(832, output.height)
                assertTrue(!output.isRecycled)

                val rendered = IntArray(576 * 832)
                output.getPixels(rendered, 0, 576, 0, 0, 576, 832)
                val hasChroma = rendered.any { pixel ->
                    val red = (pixel shr 16) and 0xff
                    val green = (pixel shr 8) and 0xff
                    val blue = pixel and 0xff
                    kotlin.math.abs(red - green) > 2 || kotlin.math.abs(blue - green) > 2
                }
                assertTrue("Synthetic result produced by calibrated NPU model has no chroma", hasChroma)
                println("[NpuMangaColorTest] SUCCESS: NPU engine colored 576x832 page with stamp=$stamp")
            } finally {
                output.recycle()
            }
        } finally {
            engine.close()
            source.recycle()
        }
    }

    @Test
    fun npuModelRunsWithFfdnetDenoisingPipeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ffdnetEngine = FfdnetDenoiserEngine(context)
        val engine = NpuMangaColorizationEngine(
            deps = NpuMangaColorizationEngine.Dependencies.from(context),
            ffdnetEngine = ffdnetEngine,
            preferFfdnetDenoising = { true },
        )
        val source = Bitmap.createBitmap(576, 832, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(576 * 832) { index ->
            val x = index % 576
            val y = index / 576
            val gray = ((x + y) / 6).coerceIn(20, 240)
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        source.setPixels(pixels, 0, 576, 0, 0, 576, 832)

        try {
            val available = engine.isAvailable()
            val error = engine.initializationError()
            assertTrue("MCV2 NPU engine failed to initialize: $error", available)

            val stamp = engine.currentStamp()
            assertNotNull("Stamp should not be null", stamp)
            assertTrue("Stamp should have FFDNET suffix", stamp!!.endsWith("+FFDNET_SMOOTH"))

            val ffdnetAvailable = ffdnetEngine.isAvailable()
            val ffdnetError = ffdnetEngine.initializationError()
            assertTrue("FFDNet engine failed to initialize: $ffdnetError", ffdnetAvailable)

            val output = engine.colorize(source, 0L)
            try {
                assertEquals(576, output.width)
                assertEquals(832, output.height)
                assertTrue(!output.isRecycled)

                val rendered = IntArray(576 * 832)
                output.getPixels(rendered, 0, 576, 0, 0, 576, 832)
                val hasChroma = rendered.any { pixel ->
                    val red = (pixel shr 16) and 0xff
                    val green = (pixel shr 8) and 0xff
                    val blue = pixel and 0xff
                    kotlin.math.abs(red - green) > 2 || kotlin.math.abs(blue - green) > 2
                }
                assertTrue("FFDNet + NPU result produced has no chroma", hasChroma)
                println("[NpuMangaColorTest] SUCCESS: Full original FFDNet + calibrated NPU pipeline executed! stamp=$stamp")
            } finally {
                output.recycle()
            }
        } finally {
            engine.close()
            source.recycle()
        }
    }
}
