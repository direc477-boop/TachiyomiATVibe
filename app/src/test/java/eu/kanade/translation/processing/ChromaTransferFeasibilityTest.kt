package eu.kanade.translation.processing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Synthetic numeric feasibility study for chroma-transfer coloring reuse:
 * output luminance comes from the cleaned (inpainted) page, chroma from a
 * fingerprint-verified cached colored original, with masked text-region
 * chroma replaced by nearby unmasked chroma.
 *
 * SCOPE: pure ARGB integer math on synthetic fixtures. These assertions are
 * development-time invariants — geometry, luminance/chroma behavior, masked
 * regions, clipping. They are NOT a per-run quality gate and say NOTHING
 * about visual quality; visual evaluation belongs to the user, and the
 * production model-rerun path stays in place until data justify reuse.
 *
 * MEASURED FINDING on this fixture: the simulated palette is saturated
 * enough that recombining its chroma with the cleaned page's HIGHER
 * luminance clips red at every pixel — chroma drifts by up to ~6 and
 * luminance by up to ~3 around clipped pixels. Clipping drift is therefore
 * a real risk of the transform, growing with palette saturation × luminance
 * shift between the cached and cleaned pages.
 */
class ChromaTransferFeasibilityTest {

    private val size = 64

    // Warm paper background and near-black synthetic "text".
    private val background = intArrayOf(245, 236, 220)
    private val textStroke = intArrayOf(20, 20, 24)

    // Simulated coloring-model output: same structure, shifted palette.
    private val coloredBackground = intArrayOf(252, 214, 196)

    private val maskRects = listOf(8..15, 40..47)
    private val maskRows = 12..44

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun rOf(px: Int) = (px shr 16) and 0xFF
    private fun gOf(px: Int) = (px shr 8) and 0xFF
    private fun bOf(px: Int) = px and 0xFF

    // BT.601 full-range, ×256 integer approximations (rounding ±1-2).
    private fun yOf(r: Int, g: Int, b: Int) = (77 * r + 150 * g + 29 * b + 128) shr 8
    private fun cbOf(r: Int, g: Int, b: Int) = (-43 * r - 85 * g + 128 * b) / 256 + 128
    private fun crOf(r: Int, g: Int, b: Int) = (128 * r - 107 * g - 21 * b) / 256 + 128

    private fun toRgb(y: Int, cb: Int, cr: Int): Int {
        val cCr = cr - 128
        val cCb = cb - 128
        val r = (y + 359 * cCr / 256).coerceIn(0, 255)
        val g = (y - 88 * cCb / 256 - 183 * cCr / 256).coerceIn(0, 255)
        val b = (y + 454 * cCb / 256).coerceIn(0, 255)
        return argb(r, g, b)
    }

    private fun isMasked(x: Int, y: Int) = y in maskRows && maskRects.any { x in it }

    /** Cached colored original: model-palette background, original text present. */
    private fun buildCachedColored(): IntArray {
        val px = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val base = if (isMasked(x, y)) textStroke else coloredBackground
                px[y * size + x] = argb(base[0], base[1], base[2])
            }
        }
        return px
    }

    /** Cleaned page: perfect inpaint — uniform background, no text anywhere. */
    private fun buildCleaned(): IntArray =
        IntArray(size * size) { argb(background[0], background[1], background[2]) }

    /**
     * The candidate reuse transform under study: chroma from the cached
     * colored original, masked-region chroma replaced by the mean unmasked
     * chroma within a window, luminance always from the cleaned page.
     */
    private fun chromaTransfer(cached: IntArray, cleaned: IntArray): IntArray {
        val n = size * size
        val cachedCb = IntArray(n) { cbOf(rOf(cached[it]), gOf(cached[it]), bOf(cached[it])) }
        val cachedCr = IntArray(n) { crOf(rOf(cached[it]), gOf(cached[it]), bOf(cached[it])) }
        val masked = BooleanArray(n) { idx -> isMasked(idx % size, idx / size) }
        val unmaskedIdx = (0 until n).filter { !masked[it] }
        val outCb = IntArray(n)
        val outCr = IntArray(n)
        for (idx in 0 until n) {
            if (!masked[idx]) {
                outCb[idx] = cachedCb[idx]
                outCr[idx] = cachedCr[idx]
            } else {
                val window = unmaskedIdx.filter {
                    abs(it % size - idx % size) <= 8 && abs(it / size - idx / size) <= 8
                }
                outCb[idx] = window.map { cachedCb[it] }.average().roundToInt()
                outCr[idx] = window.map { cachedCr[it] }.average().roundToInt()
            }
        }
        return IntArray(n) { idx ->
            val y = yOf(rOf(cleaned[idx]), gOf(cleaned[idx]), bOf(cleaned[idx]))
            toRgb(y, outCb[idx], outCr[idx])
        }
    }

    @Test
    fun `masked regions take nearby unmasked chroma, never stroke chroma`() {
        val out = chromaTransfer(buildCachedColored(), buildCleaned())
        val probe = 13 * size + 10 // inside stroke 1

        val expectedCb = cbOf(coloredBackground[0], coloredBackground[1], coloredBackground[2])
        val expectedCr = crOf(coloredBackground[0], coloredBackground[1], coloredBackground[2])
        val cbOut = cbOf(rOf(out[probe]), gOf(out[probe]), bOf(out[probe]))
        val crOut = crOf(rOf(out[probe]), gOf(out[probe]), bOf(out[probe]))

        // The masked pixel's own chroma equals the window-mean unmasked
        // chroma (the transform's contract). Recomputing chroma from the
        // output RGB carries the measured red-clip drift of this fixture —
        // the transform feeds planes, not final pixels, so assert against
        // the drift bound, not exact zero.
        (abs(cbOut - expectedCb) <= 8) shouldBe true
        (abs(crOut - expectedCr) <= 8) shouldBe true
        // Stroke chroma must not leak through (stroke is near-neutral dark).
        val cbStroke = cbOf(textStroke[0], textStroke[1], textStroke[2])
        val crStroke = crOf(textStroke[0], textStroke[1], textStroke[2])
        (abs(cbOut - cbStroke) > 10) shouldBe true
        (abs(crOut - crStroke) > 10) shouldBe true
    }

    @Test
    fun `unmasked chroma passes through within the measured clipping drift`() {
        val cached = buildCachedColored()
        val out = chromaTransfer(cached, buildCleaned())
        val probe = 0
        val cbOut = cbOf(rOf(out[probe]), gOf(out[probe]), bOf(out[probe]))
        val crOut = crOf(rOf(out[probe]), gOf(out[probe]), bOf(out[probe]))
        val cbCached = cbOf(rOf(cached[probe]), gOf(cached[probe]), bOf(cached[probe]))
        val crCached = crOf(rOf(cached[probe]), gOf(cached[probe]), bOf(cached[probe]))
        // Recombination at the cleaned page's luminance clips this palette:
        // pass-through holds only within the MEASURED drift bound.
        (abs(cbOut - cbCached) <= 8) shouldBe true
        (abs(crOut - crCached) <= 8) shouldBe true
    }

    @Test
    fun `luminance tracks the cleaned page except where recombination clips`() {
        val cleaned = buildCleaned()
        val out = chromaTransfer(buildCachedColored(), cleaned)
        var clipped = 0
        var maxDrift = 0
        for (idx in out.indices) {
            val yOut = yOf(rOf(out[idx]), gOf(out[idx]), bOf(out[idx]))
            val yCleaned = yOf(rOf(cleaned[idx]), gOf(cleaned[idx]), bOf(cleaned[idx]))
            maxDrift = max(maxDrift, abs(yOut - yCleaned))
            if (abs(yOut - yCleaned) > 0) clipped++
        }
        // Measured on this fixture: drift stays small (≤3) but is non-zero —
        // saturated cached chroma at higher cleaned luminance clips.
        (maxDrift <= 3) shouldBe true
        (clipped > 0) shouldBe true
    }

    @Test
    fun `out-of-gamut chroma clips without exception`() {
        val extreme = toRgb(255, 0, 255)
        (rOf(extreme) in 0..255) shouldBe true
        (gOf(extreme) in 0..255) shouldBe true
        (bOf(extreme) in 0..255) shouldBe true
    }
}
