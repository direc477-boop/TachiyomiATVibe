package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object MangaColorizationImageProcessing {

    /** MCV2 NCHW input channel count (grayscale + 4 conditioning planes). */
    private const val MCV2_CHANNELS = 5
    private const val RGB_CHANNELS = 3

    /** Aspect-preserving scale of [source] to the letterbox content rect, as row-major ARGB. */
    fun scaledContentArgb(source: Bitmap, content: MangaColorizationLetterbox): IntArray {
        val pixels = IntArray(content.width * content.height)
        val scaled = Bitmap.createScaledBitmap(source, content.width, content.height, true)
        try {
            scaled.getPixels(pixels, 0, content.width, 0, 0, content.width, content.height)
        } finally {
            if (scaled !== source) scaled.recycle()
        }
        return pixels
    }

    /** Packs scaled-content ARGB into the 5-ch NCHW plane: ch0 luma over paper-white pad, ch1-4 zero. */
    fun grayNchwFromArgb(
        pixels: IntArray,
        content: MangaColorizationLetterbox,
        targetWidth: Int,
        targetHeight: Int,
    ): FloatArray {
        val gray = FloatArray(targetWidth * targetHeight) { 1f }
        for (y in 0 until content.height) {
            val outputRow = (y + content.top) * targetWidth + content.left
            val inputRow = y * content.width
            for (x in 0 until content.width) {
                val argb = pixels[inputRow + x]
                val red = (argb shr 16) and 0xFF
                val green = (argb shr 8) and 0xFF
                val blue = argb and 0xFF
                gray[outputRow + x] = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
            }
        }
        val planeSize = targetWidth * targetHeight
        return FloatArray(MCV2_CHANNELS * planeSize).also {
            System.arraycopy(gray, 0, it, 0, planeSize)
        }
    }

    /** Aspect scale of [source] to the ONNX work dimensions, as row-major ARGB (no letterbox). */
    fun scaledArgb(source: Bitmap, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        try {
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            if (scaled !== source) scaled.recycle()
        }
        return pixels
    }

    /** Rec.601 luma of row-major ARGB in [0,1]. */
    fun lumaFromArgb(pixels: IntArray): FloatArray {
        val plane = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val argb = pixels[i]
            val red = (argb shr 16) and 0xFF
            val green = (argb shr 8) and 0xFF
            val blue = argb and 0xFF
            plane[i] = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
        }
        return plane
    }

    /** Crops the MCV2 interleaved RGB [0,1] output back to the letterbox content. */
    fun cropRgb01Letterbox(
        output: FloatArray,
        content: MangaColorizationLetterbox,
        targetWidth: Int,
        targetHeight: Int,
    ): FloatArray {
        val cropped = FloatArray(content.width * content.height * RGB_CHANNELS)
        for (y in 0 until content.height) {
            for (x in 0 until content.width) {
                val sourcePixel = (y + content.top) * targetWidth + x + content.left
                val sourceOffset = sourcePixel * RGB_CHANNELS
                val destinationOffset = (y * content.width + x) * RGB_CHANNELS
                cropped[destinationOffset] = output[sourceOffset].coerceIn(0f, 1f)
                cropped[destinationOffset + 1] = output[sourceOffset + 1].coerceIn(0f, 1f)
                cropped[destinationOffset + 2] = output[sourceOffset + 2].coerceIn(0f, 1f)
            }
        }
        return cropped
    }

    fun planarRgb01ToBitmap(rgb: FloatArray, width: Int, height: Int): Bitmap {
        val pixelCount = width * height
        require(rgb.size == pixelCount * 3) { "RGB buffer size does not match dimensions" }
        val pixels = IntArray(pixelCount)
        for (pixel in pixels.indices) {
            val r = (rgb[pixel].coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (rgb[pixelCount + pixel].coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (rgb[2 * pixelCount + pixel].coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[pixel] = argb(r, g, b)
        }
        return bitmapFromPixels(pixels, width, height)
    }

    fun interleavedRgb01ToBitmap(rgb: FloatArray, width: Int, height: Int): Bitmap {
        require(rgb.size == width * height * 3) { "RGB buffer size does not match dimensions" }
        val pixels = IntArray(width * height)
        for (pixel in pixels.indices) {
            val offset = pixel * 3
            val r = (rgb[offset].coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (rgb[offset + 1].coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (rgb[offset + 2].coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[pixel] = argb(r, g, b)
        }
        return bitmapFromPixels(pixels, width, height)
    }

    /** Ink-aware combine on API 33+ (AGSL) or the CPU twin otherwise/on GPU failure — never a backend fallback. */
    fun combineColoredWithSource(source: Bitmap, coloredWork: Bitmap): Bitmap {
        val (mergeWidth, mergeHeight) = mergeDimensions(source.width, source.height)
        if (AgslChromaCombiner.supported) {
            try {
                return scaleBackToSource(
                    AgslChromaCombiner.combine(source, coloredWork, mergeWidth, mergeHeight),
                    source,
                )
            } catch (failure: Throwable) {
                if (failure.isFatal()) throw failure
                logcat(LogPriority.WARN, failure) { "AGSL chroma combine failed; using CPU ink-aware merge" }
            }
        }
        return scaleBackToSource(mergeWithInkAwareCpu(source, coloredWork, mergeWidth, mergeHeight), source)
    }

    /** Capped merge dimensions preserving aspect; [MAX_MERGE_PIXELS] bounds work size on both paths. */
    fun mergeDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        val pixels = sourceWidth.toLong() * sourceHeight
        if (pixels <= MAX_MERGE_PIXELS) return sourceWidth to sourceHeight
        val shrink = sqrt(MAX_MERGE_PIXELS.toDouble() / pixels)
        val width = (sourceWidth * shrink).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * shrink).roundToInt().coerceAtLeast(1)
        return width to height
    }

    private fun scaleBackToSource(merged: Bitmap, source: Bitmap): Bitmap {
        if (merged.width == source.width && merged.height == source.height) return merged
        return Bitmap.createScaledBitmap(merged, source.width, source.height, true).also {
            if (it !== merged) merged.recycle()
        }
    }

    private fun mergeWithInkAwareCpu(source: Bitmap, coloredWork: Bitmap, mergeWidth: Int, mergeHeight: Int): Bitmap {
        val origBitmap: Bitmap
        if (source.width == mergeWidth && source.height == mergeHeight) {
            origBitmap = source
        } else {
            origBitmap = Bitmap.createScaledBitmap(source, mergeWidth, mergeHeight, true)
        }
        val colored = Bitmap.createScaledBitmap(coloredWork, mergeWidth, mergeHeight, true)
        try {
            val origRgb = bitmapToRgb01(origBitmap)
            val coloredRgb = bitmapToRgb01(colored)
            val merged = CpuChromaCombiner.merge(origRgb, coloredRgb, INK_SHADING_STRENGTH)
            return interleavedRgb01ToBitmap(merged, mergeWidth, mergeHeight)
        } finally {
            if (colored !== coloredWork) colored.recycle()
            if (origBitmap !== source) origBitmap.recycle()
        }
    }

    private fun bitmapToRgb01(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = FloatArray(pixels.size * 3)
        for (i in pixels.indices) {
            val argb = pixels[i]
            rgb[i * 3] = ((argb shr 16) and 0xFF) / 255f
            rgb[i * 3 + 1] = ((argb shr 8) and 0xFF) / 255f
            rgb[i * 3 + 2] = (argb and 0xFF) / 255f
        }
        return rgb
    }

    private fun bitmapFromPixels(pixels: IntArray, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    private const val MAX_MERGE_PIXELS = 1_500_000L
}

/** Ink-aware combine shading strength from the user's Variant C prototype. */
internal const val INK_SHADING_STRENGTH = 0.35f

/** Errors that must never be swallowed by rendering fallbacks. */
internal fun Throwable.isFatal(): Boolean = this is VirtualMachineError || this is ThreadDeath
