package eu.kanade.translation.engines.coloring

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.engines.coloring.MangaColorizationModelRequirement.INT8_ONLY
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.ColorizationQuality
import kotlin.math.min
import kotlin.math.roundToInt

internal class MangaColorizationInt8Engine(
    context: Context,
    private val quality: ColorizationQuality,
) : PageColorizationEngine {

    private val runtime = MangaColorizationOnnxSession(context, INT8_ONLY)

    override fun isAvailable(): Boolean = runtime.isAvailable()

    /** Stamp is pipeline-config identity: the suffix marks the built-in screentone smoothing stage
     *  that always runs on this route — never a per-page outcome. */
    override fun currentStamp(): String? =
        runtime.currentStamp()
            ?.plus(MangaColorizationPreprocessor.STAMP_SUFFIX)
            ?.plus("_Q_" + quality.name)

    @Synchronized
    override fun colorize(source: Bitmap, seed: Long): Bitmap {
        // seed is unused: the generator is deterministic.
        val startedAt = System.nanoTime()
        val geometry = MangaColorizationInt8Preprocessor.geometry(source.width, source.height, quality)
        // Quality-only preprocessing on the aspect-true content dims; the pad-to-32
        // happens strictly after, and stage failures degrade to the plain downsample.
        val preprocessed = MangaColorizationPreprocessor.grayPlane(
            source,
            geometry.contentWidth,
            geometry.contentHeight,
        )
        logcat(LogPriority.INFO) {
            "ScreentoneSmooth score=" + preprocessed.score + " smoothed=" + preprocessed.smoothingApplied
        }
        val input = MangaColorizationInt8Preprocessor.packGrayPlane(preprocessed.plane, geometry)
        val output = runtime.run(input, geometry.paddedHeight, geometry.paddedWidth)
        check(output.all { it.isFinite() }) { "MCV2 INT8 returned non-finite output" }
        val croppedOutput = MangaColorizationInt8Preprocessor.cropOutput(output, geometry)
        val coloredWork = MangaColorizationImageProcessing.planarRgb01ToBitmap(
            croppedOutput,
            geometry.contentWidth,
            geometry.contentHeight,
        )
        val result = try {
            MangaColorizationImageProcessing.combineColoredWithSource(source, coloredWork)
        } finally {
            coloredWork.recycle()
        }
        logcat(LogPriority.INFO) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
            "OnnxMangaColorization INT8 page done in " + elapsedMs + "ms (quality=" + quality.name +
                ", work=" + geometry.contentWidth + "x" + geometry.contentHeight +
                ", padded=" + geometry.paddedWidth + "x" + geometry.paddedHeight + ")"
        }
        return result
    }

    @Synchronized
    override fun close() {
        runtime.close()
    }
}

internal data class MangaColorizationInt8Geometry(
    val contentWidth: Int,
    val contentHeight: Int,
    val paddedWidth: Int,
    val paddedHeight: Int,
)

internal object MangaColorizationInt8Preprocessor {

    fun geometry(sourceWidth: Int, sourceHeight: Int, quality: ColorizationQuality): MangaColorizationInt8Geometry {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        val shortSide = min(sourceWidth, sourceHeight)
        val longSide = maxOf(sourceWidth, sourceHeight)
        val scale = min(
            quality.targetShortSidePx.toFloat() / shortSide,
            quality.maxLongSidePx.toFloat() / longSide,
        )
        val contentWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val contentHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        return MangaColorizationInt8Geometry(
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            paddedWidth = roundUpToMultipleOf32(contentWidth),
            paddedHeight = roundUpToMultipleOf32(contentHeight),
        )
    }

    fun packGrayPlane(contentGray: FloatArray, geometry: MangaColorizationInt8Geometry): FloatArray {
        require(contentGray.size == geometry.contentWidth * geometry.contentHeight) {
            "Grayscale buffer size does not match content dimensions"
        }
        val planeSize = geometry.paddedWidth * geometry.paddedHeight
        val gray = FloatArray(planeSize) { 1f }
        for (row in 0 until geometry.contentHeight) {
            System.arraycopy(
                contentGray,
                row * geometry.contentWidth,
                gray,
                row * geometry.paddedWidth,
                geometry.contentWidth,
            )
        }
        return FloatArray(CHANNELS * planeSize).also {
            System.arraycopy(gray, 0, it, 0, planeSize)
        }
    }

    fun cropOutput(
        paddedRgb: FloatArray,
        geometry: MangaColorizationInt8Geometry,
    ): FloatArray {
        val paddedPlaneSize = geometry.paddedWidth * geometry.paddedHeight
        require(paddedRgb.size == CHANNELS_RGB * paddedPlaneSize) {
            "RGB output size does not match padded dimensions"
        }
        val contentPlaneSize = geometry.contentWidth * geometry.contentHeight
        val cropped = FloatArray(CHANNELS_RGB * contentPlaneSize)
        for (channel in 0 until CHANNELS_RGB) {
            for (row in 0 until geometry.contentHeight) {
                System.arraycopy(
                    paddedRgb,
                    channel * paddedPlaneSize + row * geometry.paddedWidth,
                    cropped,
                    channel * contentPlaneSize + row * geometry.contentWidth,
                    geometry.contentWidth,
                )
            }
        }
        return cropped
    }

    private fun roundUpToMultipleOf32(dimension: Int): Int = ((dimension + 31) / 32) * 32

    private const val CHANNELS = 5
    private const val CHANNELS_RGB = 3
}
