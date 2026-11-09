package eu.kanade.translation.engines.coloring

import android.content.Context
import android.graphics.Bitmap
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.min

/** Deterministic MCV2 ONNX CPU fallback; prefers dynamic INT8 weights, fp32-widened only when INT8 is missing. */
class OnnxMangaColorizationEngine(context: Context) : PageColorizationEngine {

    private val runtime = MangaColorizationOnnxSession(
        context,
        MangaColorizationModelRequirement.PREFER_INT8,
    )

    override fun isAvailable(): Boolean = runtime.isAvailable()

    /** Stamp is pipeline-config identity: the suffix marks the built-in screentone smoothing stage
     *  that always runs on this route — never a per-page outcome. */
    @Synchronized
    override fun currentStamp(): String? =
        runtime.currentStamp()?.plus(MangaColorizationPreprocessor.STAMP_SUFFIX)

    @Synchronized
    override fun colorize(source: Bitmap, seed: Long): Bitmap {
        // seed is unused: the generator is deterministic (no sampler).
        val startedAt = System.nanoTime()
        val (workWidth, workHeight) = workDimensions(source.width, source.height)
        val preprocessed = MangaColorizationPreprocessor.grayPlane(source, workWidth, workHeight)
        logcat(LogPriority.INFO) {
            "ScreentoneSmooth score=" + preprocessed.score + " smoothed=" + preprocessed.smoothingApplied
        }
        val input = FloatArray(CHANNELS * workWidth * workHeight)
        System.arraycopy(preprocessed.plane, 0, input, 0, preprocessed.plane.size)
        val output = runtime.run(input, workHeight, workWidth)
        val coloredWork = MangaColorizationImageProcessing.planarRgb01ToBitmap(output, workWidth, workHeight)
        val colored = try {
            MangaColorizationImageProcessing.combineColoredWithSource(source, coloredWork)
        } finally {
            coloredWork.recycle()
        }
        logcat(LogPriority.INFO) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
            val precision = if (runtime.currentStamp()?.contains("INT8") == true) "int8" else "fp32"
            "OnnxMangaColorization page done in " + elapsedMs + "ms " +
                "(cpu " + precision + ", work=" + workWidth + "x" + workHeight + ")"
        }
        return colored
    }

    @Synchronized
    override fun close() {
        runtime.close()
    }

    private fun workDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val shortSide = min(sourceWidth, sourceHeight).coerceAtLeast(1)
        var scale = WORK_SHORT_SIDE.toFloat() / shortSide
        val longSide = maxOf(sourceWidth, sourceHeight)
        if (longSide * scale > WORK_LONG_SIDE_MAX) {
            scale = WORK_LONG_SIDE_MAX.toFloat() / longSide
        }
        val width = ((sourceWidth * scale).toInt() / 32) * 32
        val height = ((sourceHeight * scale).toInt() / 32) * 32
        return Pair(width.coerceAtLeast(32), height.coerceAtLeast(32))
    }

    private companion object {
        const val CHANNELS = 5
        const val WORK_SHORT_SIDE = 512
        const val WORK_LONG_SIDE_MAX = 1536
    }
}
