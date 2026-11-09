package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaLightHybridDeviceTest {
    @Test
    fun syntheticPageRunsThroughSamAndGenerator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = MangaLightHybridColorizationEngine(context)
        val source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(512 * 512) { index ->
            val gray = (index % 512) / 2
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        source.setPixels(pixels, 0, 512, 0, 0, 512, 512)
        try {
            val available = engine.isAvailable()
            assertTrue(engine.initializationError() ?: "Hybrid runtime unavailable", available)
            val output = engine.colorize(source, 0L)
            try {
                assertEquals(512, output.width)
                assertEquals(512, output.height)
                assertTrue(!output.isRecycled)
                val rendered = IntArray(512 * 512)
                output.getPixels(rendered, 0, 512, 0, 0, 512, 512)
                assertTrue("Synthetic result has no chroma", rendered.any { pixel ->
                    val red = (pixel shr 16) and 0xff
                    val green = (pixel shr 8) and 0xff
                    val blue = pixel and 0xff
                    kotlin.math.abs(red - green) > 2 || kotlin.math.abs(blue - green) > 2
                })
            } finally {
                output.recycle()
            }
        } finally {
            engine.close()
            source.recycle()
        }
    }
}
