package eu.kanade.translation.engines.coloring

/** Selective midtone smoothing: cv2.bilateralFilter(uint8 gray, d=5, sigmaColor=35, sigmaSpace=35)
 *  with reflect-101 borders, then prototype ink protection — original restored where the value is < 35
 *  (the prototype's uint8 form of "luma < 0.14"; pixel value 35 itself is NOT protected). */
internal object SelectiveBilateral {

    const val DIAMETER = 5
    const val SIGMA_COLOR = 35.0
    const val SIGMA_SPACE = 35.0
    const val INK_THRESHOLD = 35

    private val RADIUS = DIAMETER / 2
    private val SPATIAL = DoubleArray(DIAMETER) { i ->
        val d = i - RADIUS
        kotlin.math.exp(-(d.toDouble() * d) / (2.0 * SIGMA_SPACE * SIGMA_SPACE))
    }
    private val COLOR_DENOMINATOR = 2.0 * SIGMA_COLOR * SIGMA_COLOR
    private val KERNEL_WEIGHTS = Array(DIAMETER * DIAMETER) { k ->
        val dy = k / DIAMETER - RADIUS
        val dx = k % DIAMETER - RADIUS
        val spatial = SPATIAL[dy + RADIUS] * SPATIAL[dx + RADIUS]
        DoubleArray(256) { diff ->
            spatial * kotlin.math.exp(-(diff.toDouble() * diff) / COLOR_DENOMINATOR)
        }
    }

    /** Smooths [gray] ([width]x[height], row-major unsigned bytes) and re-applies ink protection. */
    fun apply(gray: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0) { "invalid plane dimensions" }
        require(gray.size == width * height) { "plane buffer does not match dimensions" }
        val filtered = ByteArray(gray.size)
        val reflectedRows = IntArray(DIAMETER)
        for (y in 0 until height) {
            for (dyIdx in 0 until DIAMETER) {
                reflectedRows[dyIdx] = reflect(y + dyIdx - RADIUS, height) * width
            }
            val rowOffset = y * width
            for (x in 0 until width) {
                val center = gray[rowOffset + x].toInt() and 0xFF
                var sum = 0.0
                var weights = 0.0
                var k = 0
                for (dyIdx in 0 until DIAMETER) {
                    val syRow = reflectedRows[dyIdx]
                    for (dx in -RADIUS..RADIUS) {
                        val sx = reflect(x + dx, width)
                        val neighbor = gray[syRow + sx].toInt() and 0xFF
                        val diff = kotlin.math.abs(neighbor - center)
                        val weight = KERNEL_WEIGHTS[k++][diff]
                        sum += weight * neighbor
                        weights += weight
                    }
                }
                filtered[rowOffset + x] = (sum / weights + 0.5).toInt().coerceIn(0, 255).toByte()
            }
        }
        for (i in gray.indices) {
            if ((gray[i].toInt() and 0xFF) < INK_THRESHOLD) filtered[i] = gray[i]
        }
        return filtered
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
