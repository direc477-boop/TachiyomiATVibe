package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** User-specified fast preprocessing seam (FFDNet replacement): full-resolution source plus target work
 *  dims in, normalized float gray plane (generator channel 0) out. Gate -> area downsample -> selective
 *  bilateral on screentone pages. Recoverable smoothing failures degrade to the plain downsample; fatal
 *  (OOM-family) errors always rethrow. */
internal object MangaColorizationPreprocessor {

    const val STAMP_SUFFIX = "+BILATERAL_SMOOTH"

    class GrayPlane(val plane: FloatArray, val score: Float, val smoothingApplied: Boolean)

    /** Full-resolution [source] aspect-true content dims [workWidth]x[workHeight] -> gray plane. */
    fun grayPlane(source: Bitmap, workWidth: Int, workHeight: Int): GrayPlane {
        require(workWidth > 0 && workHeight > 0) { "invalid work dimensions" }
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return planeFromGray(grayLFromArgb(pixels), source.width, source.height, workWidth, workHeight)
    }

    /** Pure composition over an unsigned uint8 gray plane (JVM-testable numeric twin target). */
    fun planeFromGray(gray: ByteArray, width: Int, height: Int, workWidth: Int, workHeight: Int): GrayPlane {
        val score = ScreentoneGate.score(gray, width, height)
        var work = AreaResampler.resample(gray, width, height, workWidth, workHeight)
        var applied = false
        if (score >= ScreentoneGate.THRESHOLD) {
            applied = true
            work = try {
                SelectiveBilateral.apply(work, workWidth, workHeight)
            } catch (failure: Throwable) {
                if (failure.isFatal()) throw failure
                logcat(LogPriority.ERROR, failure) { "ScreentoneSmooth: bilateral failed; degraded to plain downsample" }
                applied = false
                AreaResampler.resample(gray, width, height, workWidth, workHeight)
            }
        }
        val plane = FloatArray(work.size)
        for (i in work.indices) plane[i] = (work[i].toInt() and 0xFF) / 255f
        return GrayPlane(plane, score, applied)
    }

    /** PIL convert("L") fixed point: (19595*R + 38470*G + 7471*B + 32768) >> 16, empirically exact. */
    fun grayLFromArgb(argb: IntArray): ByteArray {
        val gray = ByteArray(argb.size)
        for (i in argb.indices) {
            val pixel = argb[i]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            gray[i] = ((19595 * red + 38470 * green + 7471 * blue + 32768) shr 16).toByte()
        }
        return gray
    }

    /** Packs the content gray plane into the 5-ch NCHW input: ch0 luma over paper-white pad, ch1-4 zero. */
    fun grayNchwFromGrayPlane(
        gray: FloatArray,
        content: MangaColorizationLetterbox,
        targetWidth: Int,
        targetHeight: Int,
    ): FloatArray {
        require(gray.size == content.width * content.height) { "gray plane does not match content rect" }
        val packed = FloatArray(targetWidth * targetHeight) { 1f }
        for (y in 0 until content.height) {
            val outputRow = (y + content.top) * targetWidth + content.left
            System.arraycopy(gray, y * content.width, packed, outputRow, content.width)
        }
        val planeSize = targetWidth * targetHeight
        return FloatArray(MCV2_CHANNELS * planeSize).also {
            System.arraycopy(packed, 0, it, 0, planeSize)
        }
    }

    private const val MCV2_CHANNELS = 5
}
