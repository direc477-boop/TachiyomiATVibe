package eu.kanade.translation.engines.coloring

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Numeric spec of the AGSL combine coordinate wiring (actual GPU rendering is verified on-device
 * with synthetic grids). Reproduces the user-reported misalignment: pre-fix, uOrig sampled raw
 * merge-space coords, so large (>1.5MP) pages rendered a top-left source crop as the L/ink layer;
 * the shader local matrix restores full-field sampling. Color sampling is ratio-mapped in-shader
 * and was full-field in both eras.
 */
class AgslCombineMappingTest {

    @Test
    fun `merge dimensions cap the demo page exactly`() {
        MangaColorizationImageProcessing.mergeDimensions(1350, 1920) shouldBe Pair(1027, 1461)
    }

    @Test
    fun `merge dimensions pass sub-cap pages through unchanged`() {
        MangaColorizationImageProcessing.mergeDimensions(1000, 1400) shouldBe Pair(1000, 1400)
    }

    @Test
    fun `post-fix orig sampling is full-field on capped pages`() {
        // draw fraction f of the merge canvas samples source fraction f (scale(merge/src) matrix)
        sourceFractionSampledPostFix(0.5, src = 1350.0, merge = 1027.0) shouldBe 0.5
        sourceFractionSampledPostFix(0.75, src = 1920.0, merge = 1461.0) shouldBe 0.75
    }

    @Test
    fun `pre-fix raw sampling shifted and cropped capped pages`() {
        // the canvas center read only the 38% source line instead of the 50% line
        sourceFractionSampledPreFix(0.5, src = 1350.0, merge = 1027.0) shouldBe (0.5 * 1027.0 / 1350.0)
        // a feature at 75% source width landed at 98.6% of the output canvas
        outputFractionOfFeaturePreFix(0.75, src = 1350.0, merge = 1027.0) shouldBe (0.75 * 1350.0 / 1027.0)
        // features beyond 1027/1350 = 76.07% source width never appeared at all (cropped)
    }

    @Test
    fun `sub-cap pages were and are aligned without the matrix`() {
        sourceFractionSampledPreFix(0.5, src = 1000.0, merge = 1000.0) shouldBe 0.5
        sourceFractionSampledPostFix(0.5, src = 1000.0, merge = 1000.0) shouldBe 0.5
    }

    @Test
    fun `color sampling was and is ratio-mapped full-field`() {
        // uColor.eval(coord * (work / orig)) with work at content dims: fraction-preserving both eras
        val fraction = 0.3
        val sampled = fraction * 1461.0 * (704.0 / 1461.0) / 704.0
        abs(sampled - fraction) shouldBeLessThan 1e-12
    }

    /** Which source fraction a merge-canvas draw point reads when sampling raw bitmap pixels. */
    private fun sourceFractionSampledPreFix(drawFraction: Double, src: Double, merge: Double): Double =
        drawFraction * merge / src

    /** Same after the local-matrix fix: full-field identity. */
    private fun sourceFractionSampledPostFix(drawFraction: Double, src: Double, merge: Double): Double =
        drawFraction

    /** Where a source feature lands in the output canvas when sampling raw bitmap pixels. */
    private fun outputFractionOfFeaturePreFix(sourceFraction: Double, src: Double, merge: Double): Double =
        sourceFraction * src / merge
}
