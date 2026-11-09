package eu.kanade.translation.engines.upscaling

import android.graphics.Bitmap

/**
 * Interface defining page upscaling engines (e.g. QuickSRNetLarge).
 */
interface PageUpscalingEngine : AutoCloseable {

    /** Returns true if the model artifact and hardware acceleration runtime are ready. */
    fun isAvailable(): Boolean

    /** Returns a descriptive failure message if initialization failed. */
    fun initializationError(): String?

    /**
     * Upscales [source] Bitmap by 4x using halo tiling.
     * Enforces proactive policy bounds ([MAX_INPUT_DIMENSION], [MAX_OUTPUT_PIXELS]).
     * Fatal VM errors (OOM) rethrow directly.
     */
    fun upscalePage(source: Bitmap): Bitmap

    companion object {
        const val SCALE = 4
        const val MAX_INPUT_DIMENSION = 2048
        const val MAX_OUTPUT_DIMENSION = MAX_INPUT_DIMENSION * SCALE // 8192
        const val MAX_OUTPUT_PIXELS = 48_000_000L // 48 MP -> ~192MB ARGB_8888
    }
}
