package eu.kanade.translation.engines.coloring

import kotlin.math.abs

/**
 * CPU twin of AgslChromaCombiner's Variant C combine (the user's stage3_merge_experiment formula):
 * Rec.601 gray -> smoothstep(0.12, 0.45) ink protection -> strength-scaled L mix; a/b from the work
 * layer; output clamped to [0,1]. Lab conversion reuses LineArtMerger's kornia-pinned helpers, which
 * are formula-equivalent to the AGSL helpers, so both paths compute the same math on different
 * hardware — this is not a rendered-bitmap parity claim.
 */
internal object CpuChromaCombiner {

    fun merge(origRgb01: FloatArray, coloredRgb01: FloatArray, shadingStrength: Float): FloatArray {
        require(origRgb01.size == coloredRgb01.size) { "orig/work buffer size mismatch" }
        require(shadingStrength in 0f..1f) { "shading strength must be in [0,1]" }
        val origLab = LineArtMerger.rgbToLab(origRgb01)
        val coloredLab = LineArtMerger.rgbToLab(coloredRgb01)
        val merged = FloatArray(origRgb01.size)
        for (p in 0 until origRgb01.size / 3) {
            val offset = p * 3
            val gray = 0.299f * origRgb01[offset] +
                0.587f * origRgb01[offset + 1] +
                0.114f * origRgb01[offset + 2]
            val weight = shadingStrength * smoothstep(INK_EDGE_LOW, INK_EDGE_HIGH, gray)
            merged[offset] = origLab[offset] * (1f - weight) + coloredLab[offset] * weight
            merged[offset + 1] = coloredLab[offset + 1]
            merged[offset + 2] = coloredLab[offset + 2]
        }
        return LineArtMerger.labToRgb(merged)
    }

    /** Mirrors AGSL/prototype smoothstep: clamped Hermite ramp between the ink-protection edges. */
    private fun smoothstep(edgeLow: Float, edgeHigh: Float, value: Float): Float {
        val t = ((value - edgeLow) / abs(edgeHigh - edgeLow)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val INK_EDGE_LOW = 0.12f
    private const val INK_EDGE_HIGH = 0.45f
}
