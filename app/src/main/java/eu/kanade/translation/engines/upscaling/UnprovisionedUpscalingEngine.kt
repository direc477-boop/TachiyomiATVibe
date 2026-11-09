package eu.kanade.translation.engines.upscaling

import android.graphics.Bitmap

/**
 * Placeholder engine when QuickSRNetLarge is selected or enabled while unprovisioned/unverified.
 * Surfaces an explicit visible error instead of silent fallback.
 */
class UnprovisionedUpscalingEngine(
    private val failureMessage: String = "QuickSRNetLarge upscaling is unprovisioned; hardware validation pending",
) : PageUpscalingEngine {

    override fun isAvailable(): Boolean = false

    override fun initializationError(): String = failureMessage

    override fun upscalePage(source: Bitmap): Bitmap {
        throw IllegalStateException(failureMessage)
    }

    override fun close() = Unit
}
