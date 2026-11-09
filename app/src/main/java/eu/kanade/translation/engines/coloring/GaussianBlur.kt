package eu.kanade.translation.engines.coloring

import kotlin.math.ceil
import kotlin.math.exp

/**
 * Separable Gaussian (kernel size 2*ceil(3*sigma)+1, normalized, reflect-padded)
 * matching kornia gaussian_blur2d. Shared verbatim by the line-art merge and the
 * screentone gate; the experiment's numeric twin is run_mcv2.gaussian_blur_f32.
 */
internal object GaussianBlur {

    /** Blurs [input] (row-major, [width]x[height]) into [out]. */
    fun blur(input: FloatArray, width: Int, height: Int, sigma: Float, out: FloatArray) {
        require(sigma > 0f) { "blur sigma must be positive" }
        val radius = ceil(3.0 * sigma).toInt()
        val size = 2 * radius + 1
        val kernel = FloatArray(size)
        var sum = 0f
        for (i in kernel.indices) {
            val offset = (i - radius).toFloat()
            kernel[i] = exp(-(offset * offset) / (2f * sigma * sigma))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum

        val horizontal = FloatArray(input.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (k in kernel.indices) {
                    acc += kernel[k] * input[y * width + reflect(x + k - radius, width)]
                }
                horizontal[y * width + x] = acc
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (k in kernel.indices) {
                    acc += kernel[k] * horizontal[reflect(y + k - radius, height) * width + x]
                }
                out[y * width + x] = acc
            }
        }
    }

    private fun reflect(index: Int, size: Int): Int {
        if (size == 1) return 0
        val period = 2 * size - 2
        var value = index % period
        if (value < 0) value += period
        return if (value >= size) period - value else value
    }
}
