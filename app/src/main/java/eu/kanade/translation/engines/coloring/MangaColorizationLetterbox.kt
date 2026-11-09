package eu.kanade.translation.engines.coloring

import kotlin.math.min
import kotlin.math.roundToInt

/** Aspect-preserving letterbox geometry shared by the fixed-shape colorization preprocessors. */
internal data class MangaColorizationLetterbox(
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
) {
    val requiresCpuFallback: Boolean
        get() = minOf(width, height) < MINIMUM_GPU_CONTENT_DIMENSION

    companion object {
        private const val MINIMUM_GPU_CONTENT_DIMENSION = 256

        fun fit(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): MangaColorizationLetterbox {
            require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
            require(targetWidth > 0 && targetHeight > 0) { "Target dimensions must be positive" }
            val scale = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
            val width = (sourceWidth * scale).roundToInt().coerceIn(1, targetWidth)
            val height = (sourceHeight * scale).roundToInt().coerceIn(1, targetHeight)
            return MangaColorizationLetterbox(
                width = width,
                height = height,
                left = (targetWidth - width) / 2,
                top = (targetHeight - height) / 2,
            )
        }
    }
}
