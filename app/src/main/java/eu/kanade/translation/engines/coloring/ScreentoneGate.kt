package eu.kanade.translation.engines.coloring

import kotlin.math.exp
import kotlin.math.round

/** Prototype-exact screentone detector over the FULL-RESOLUTION uint8 gray plane (user's Option C spec):
 *  384-wide area thumbnail -> cv2-style 7-tap sigma-3 gaussian high-pass -> strict-midtone mean square.
 *  Scores are pinned by JVM tests against the numeric twin of this exact implementation. */
internal object ScreentoneGate {

    const val THRESHOLD = 2.5f
    private const val THUMBNAIL_WIDTH = 384
    private val GAUSSIAN_7_SIGMA_3 = DoubleArray(7) { i ->
        val d = i - 3
        exp(-(d.toDouble() * d) / 18.0)
    }.also { kernel ->
        var sum = 0.0
        for (k in kernel) sum += k
        for (i in kernel.indices) kernel[i] /= sum
    }

    /** High-pass score = midtone area x mean hp squared x 1000 on the [THUMBNAIL_WIDTH]-wide thumbnail. */
    fun score(gray: ByteArray, width: Int, height: Int): Float {
        require(width > 0 && height > 0) { "invalid plane dimensions" }
        require(gray.size == width * height) { "plane buffer does not match dimensions" }
        val thumbHeight = round(height.toDouble() * THUMBNAIL_WIDTH / width).toInt().coerceAtLeast(1)
        val thumb = AreaResampler.resample(gray, width, height, THUMBNAIL_WIDTH, thumbHeight)
        val thumb01 = FloatArray(thumb.size)
        for (i in thumb.indices) thumb01[i] = (thumb[i].toInt() and 0xFF) / 255f
        val blurred = gaussian7Sigma3(thumb01, THUMBNAIL_WIDTH, thumbHeight)
        var midtone = 0L
        var squared = 0.0
        for (p in thumb01.indices) {
            val value = thumb01[p]
            if (value > 0.25f && value < 0.75f) {
                midtone++
                val highPass = (value - blurred[p]).toDouble()
                squared += highPass * highPass
            }
        }
        if (midtone == 0L) return 0f
        val area = midtone.toDouble() / thumb01.size
        return (area * (squared / midtone) * 1000.0).toFloat()
    }

    /** cv2.GaussianBlur(ksize=(7,7), sigma=3): fixed 7-tap kernel, reflect-101 borders, float32 plane. */
    private fun gaussian7Sigma3(plane: FloatArray, width: Int, height: Int): FloatArray {
        val kernel = GAUSSIAN_7_SIGMA_3
        val horizontal = FloatArray(plane.size)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                var acc = 0.0
                for (i in kernel.indices) {
                    acc += kernel[i] * plane[rowOffset + reflect(x + i - 3, width)]
                }
                horizontal[rowOffset + x] = acc.toFloat()
            }
        }
        val result = FloatArray(plane.size)
        val reflectedRows = IntArray(7)
        for (y in 0 until height) {
            for (i in 0 until 7) {
                reflectedRows[i] = reflect(y + i - 3, height) * width
            }
            val rowOffset = y * width
            for (x in 0 until width) {
                var acc = 0.0
                for (i in kernel.indices) {
                    acc += kernel[i] * horizontal[reflectedRows[i] + x]
                }
                result[rowOffset + x] = acc.toFloat()
            }
        }
        return result
    }

    /** cv2 BORDER_REFLECT_101: the edge pixel is mirrored without repeating it. */
    private fun reflect(index: Int, size: Int): Int {
        if (index >= 0 && index < size) return index
        if (size == 1) return 0
        val period = 2 * size - 2
        val value = Math.floorMod(index, period)
        return if (value >= size) period - value else value
    }
}
