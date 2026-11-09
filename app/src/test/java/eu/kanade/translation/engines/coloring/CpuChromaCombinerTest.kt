package eu.kanade.translation.engines.coloring

import io.kotest.matchers.floats.shouldBeLessThan
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CpuChromaCombinerTest {

    @Test
    fun `ink pixels keep original lightness regardless of strength`() {
        val orig = plane(0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f)
        val colored = plane(0.9f, 0.2f, 0.2f, 0.2f, 0.9f, 0.3f)
        val merged = CpuChromaCombiner.merge(orig, colored, INK_SHADING_STRENGTH)
        val origLab = LineArtMerger.rgbToLab(orig)
        val coloredLab = LineArtMerger.rgbToLab(colored)
        val expected = LineArtMerger.labToRgb(
            floatArrayOf(origLab[0], coloredLab[1], coloredLab[2], origLab[3], coloredLab[4], coloredLab[5]),
        )
        for (i in merged.indices) abs(merged[i] - expected[i]) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `ink zone output is strength independent`() {
        val orig = plane(0.05f, 0.05f, 0.05f, 0.0f, 0.0f, 0.0f, 0.12f, 0.12f, 0.12f)
        val colored = plane(0.8f, 0.3f, 0.3f, 0.3f, 0.8f, 0.3f, 0.3f, 0.3f, 0.8f)
        val weak = CpuChromaCombiner.merge(orig, colored, 0.35f)
        val strong = CpuChromaCombiner.merge(orig, colored, 1f)
        for (i in weak.indices) abs(weak[i] - strong[i]) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `flat regions blend lightness by strength`() {
        val orig = plane(1f, 1f, 1f)
        val colored = plane(0.9f, 0.9f, 0.85f)
        val merged = CpuChromaCombiner.merge(orig, colored, INK_SHADING_STRENGTH)
        val origLab = LineArtMerger.rgbToLab(orig)
        val coloredLab = LineArtMerger.rgbToLab(colored)
        val expectedL = 0.65f * origLab[0] + 0.35f * coloredLab[0]
        val expected = LineArtMerger.labToRgb(floatArrayOf(expectedL, coloredLab[1], coloredLab[2]))
        for (i in merged.indices) abs(merged[i] - expected[i]) shouldBeLessThan TOLERANCE
    }

    @Test
    fun `chroma passes through from the work layer`() {
        val orig = plane(0.5f, 0.5f, 0.5f)
        val colored = plane(0.55f, 0.5f, 0.45f)
        val merged = CpuChromaCombiner.merge(orig, colored, INK_SHADING_STRENGTH)
        val mergedLab = LineArtMerger.rgbToLab(merged)
        val coloredLab = LineArtMerger.rgbToLab(colored)
        // a/b passthrough is exact in Lab space; the RGB round trip re-derives it from
        // float32 cbrt differences amplified by the 500/200 factors — measured 1.25e-3 (a)
        // and 2.51e-3 (b) for this vector. Real passthrough bugs are orders larger.
        abs(mergedLab[1] - coloredLab[1]) shouldBeLessThan ROUNDTRIP_TOLERANCE
        abs(mergedLab[2] - coloredLab[2]) shouldBeLessThan ROUNDTRIP_TOLERANCE
    }

    /** Interleaved RGB01 values: each triple is one pixel. */
    private fun plane(vararg channels: Float): FloatArray = channels.toList().toFloatArray()

    private companion object {
        const val TOLERANCE = 1e-3f
        const val ROUNDTRIP_TOLERANCE = 5e-3f
    }
}
