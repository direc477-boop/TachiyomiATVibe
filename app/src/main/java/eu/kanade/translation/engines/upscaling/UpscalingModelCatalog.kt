package eu.kanade.translation.engines.upscaling

import android.content.Context

/**
 * Gate and factory for QuickSRNetLarge availability.
 * [operational] is enabled for experimental device testing (SM8650 NPU);
 * the toggle in settings remains default-OFF, and invoking the engine fails
 * explicitly and visibly if Qualcomm NPU libraries or artifacts are missing,
 * enforcing zero silent fallback.
 */
object UpscalingModelCatalog {

    /** Enabled for experimental on-device testing. */
    var operational: Boolean = true

    /**
     * Instantiates the active upscaling engine.
     * When [operational] is false, returns an [UnprovisionedUpscalingEngine] that
     * surfaces an explicit visible error if invoked, preventing any silent fallback.
     */
    fun createEngine(context: Context): PageUpscalingEngine {
        return if (operational) {
            LiteRTQuickSRNetUpscalingEngine(context)
        } else {
            UnprovisionedUpscalingEngine(
                "QuickSRNetLarge upscaling is unprovisioned; hardware validation pending on SM8650 NPU",
            )
        }
    }
}
