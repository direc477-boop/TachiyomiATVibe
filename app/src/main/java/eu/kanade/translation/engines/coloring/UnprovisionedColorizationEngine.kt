package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap

/**
 * Route for a selectable-but-unprovisioned NPU variant: reports unavailable and
 * hard-fails colorization with the provision reason, so the UI never silently
 * runs a different artifact than the one the user selected. Replaced by real
 * dispatch when the variant's artifact staging lands.
 */
internal class UnprovisionedColorizationEngine(private val reason: String) : PageColorizationEngine {

    override fun isAvailable(): Boolean = false

    override fun currentStamp(): String? = null

    override fun initializationError(): String? = reason

    override fun colorize(source: Bitmap, seed: Long): Bitmap = error(reason)

    override fun close() {}
}
