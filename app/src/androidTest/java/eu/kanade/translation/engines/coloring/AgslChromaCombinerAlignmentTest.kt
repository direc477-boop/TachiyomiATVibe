package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * On-device synthetic alignment check for the AGSL chroma combine (numeric only; no bitmaps leave
 * the process, no user images). Regression guard for the uOrig local-matrix fix: pre-fix, >1.5MP
 * pages sampled raw merge-space coords, cropping the source's top-left region into the L/ink layer
 * while the color layer stretched full-frame (the user-reported misalignment). Each case asserts:
 * pinned merge dimensions, whole-frame parity with CpuChromaCombiner at the agreed thresholds,
 * zero-shift correlation, analytic point values at five fractions, and centroid position of a
 * source-layer ink square and a work-layer chroma marker.
 */
@RunWith(AndroidJUnit4::class)
class AgslChromaCombinerAlignmentTest {

    /** Sweep: source dims, work dims (content-crop dims the pipeline feeds the combiner), label. */
    private class Case(val sw: Int, val sh: Int, val ww: Int, val wh: Int, val label: String)

    private val cases = listOf(
        Case(1350, 1920, 495, 704, "portrait_user_capped"),
        Case(1920, 1350, 512, 360, "landscape_capped"),
        Case(1000, 1400, 503, 704, "portrait_control_subcap"),
        Case(1400, 1000, 512, 366, "landscape_control_subcap"),
        Case(1500, 1000, 512, 341, "cap_boundary_exact"),
        Case(1600, 1200, 512, 384, "just_over_cap"),
        Case(1920, 700, 512, 187, "extreme_aspect_letterbox"),
    )

    /** mergeDimensions pinned per case; the user's page pair is the regression anchor. */
    private val expectedMerge = mapOf(
        "portrait_user_capped" to (1027 to 1461),
        "landscape_capped" to (1461 to 1027),
        "portrait_control_subcap" to (1000 to 1400),
        "landscape_control_subcap" to (1400 to 1000),
        "cap_boundary_exact" to (1500 to 1000),
        "just_over_cap" to (1414 to 1061),
        "extreme_aspect_letterbox" to (1920 to 700),
    )

    @Test
    fun agslCombineAlignmentSweep() {
        assumeTrue(AgslChromaCombiner.supported)
        for (case in cases) {
            runCase(case)
        }
    }

    @Test
    fun combineColoredWithSourcePublishesSourceDims() {
        assumeTrue(AgslChromaCombiner.supported)
        val case = cases.first()
        val source = buildSource(case.sw, case.sh)
        val work = buildWork(case.ww, case.wh)
        try {
            val published = MangaColorizationImageProcessing.combineColoredWithSource(source, work)
            try {
                assertEquals("${case.label} published width", case.sw, published.width)
                assertEquals("${case.label} published height", case.sh, published.height)
            } finally {
                published.recycle()
            }
        } finally {
            source.recycle()
            work.recycle()
        }
    }

    private fun runCase(case: Case) {
        val (mw, mh) = MangaColorizationImageProcessing.mergeDimensions(case.sw, case.sh)
        assertEquals(
            "${case.label} merge width pinned",
            expectedMerge.getValue(case.label).first,
            mw,
        )
        assertEquals(
            "${case.label} merge height pinned",
            expectedMerge.getValue(case.label).second,
            mh,
        )

        val source = buildSource(case.sw, case.sh)
        val work = buildWork(case.ww, case.wh)
        try {
            val started = System.nanoTime()
            val agsl = AgslChromaCombiner.combine(source, work, mw, mh)
            val agslMs = (System.nanoTime() - started) / 1_000_000.0
            try {
                assertEquals("${case.label} combine dims", mw to mh, agsl.width to agsl.height)

                val reference = cpuReference(source, work, mw, mh)
                try {
                    compareWholeFrame(case.label, agsl, reference)
                    assertZeroShiftPeak(case.label, agsl, reference)
                    assertAnalyticPoints(case.label, agsl, mw, mh)
                    assertInkSquareCentroid(case.label, agsl, mw, mh)
                    assertMarkerCentroid(case.label, agsl, mw, mh)
                } finally {
                    reference.recycle()
                }
                println(
                    "[AgslAlign] case=${case.label} agslMs=$agslMs mergeDims=${mw}x$mh " +
                        "srcDims=${case.sw}x${case.sh} workDims=${case.ww}x${case.wh}",
                )
            } finally {
                agsl.recycle()
            }
        } finally {
            source.recycle()
            work.recycle()
        }
    }

    /** CPU twin on the same merge-dims inputs, mirroring mergeWithInkAwareCpu's scaling. */
    private fun cpuReference(source: Bitmap, work: Bitmap, mw: Int, mh: Int): Bitmap {
        val origScaled = scaleTo(source, mw, mh)
        val workScaled = scaleTo(work, mw, mh)
        try {
            val merged = CpuChromaCombiner.merge(
                interleavedRgb01(origScaled),
                interleavedRgb01(workScaled),
                INK_SHADING_STRENGTH,
            )
            return MangaColorizationImageProcessing.interleavedRgb01ToBitmap(merged, mw, mh)
        } finally {
            if (origScaled !== source) origScaled.recycle()
            if (workScaled !== work) workScaled.recycle()
        }
    }

    /** Agreed whole-frame guard: p99 ≤ 4/255, max ≤ 8/255 per channel; >2/255 fraction reported. */
    private fun compareWholeFrame(label: String, agsl: Bitmap, reference: Bitmap) {
        val w = agsl.width
        val h = agsl.height
        val agslPx = IntArray(w * h).also { agsl.getPixels(it, 0, w, 0, 0, w, h) }
        val refPx = IntArray(w * h).also { reference.getPixels(it, 0, w, 0, 0, w, h) }
        val maxByChannel = IntArray(3)
        val histograms = Array(3) { IntArray(256) }
        var overTwo = 0
        for (p in agslPx.indices) {
            val a = agslPx[p]
            val r = refPx[p]
            for (c in 0 until 3) {
                val shift = 16 - 8 * c
                val diff = abs(((a shr shift) and 0xFF) - ((r shr shift) and 0xFF))
                maxByChannel[c] = maxOf(maxByChannel[c], diff)
                histograms[c][diff.coerceAtMost(255)]++
                if (diff > 2) overTwo++
            }
        }
        val total = w * h * 3
        val p99 = IntArray(3) { c -> percentile(histograms[c], w * h, 0.99) }
        println(
            "[AgslAlign] wholeframe label=$label maxAbs=${maxByChannel.toList()} " +
                "p99=${p99.toList()} overTwoFraction=${"%.5f".format(overTwo.toDouble() / total)}",
        )
        for (c in 0 until 3) {
            assertTrue("$label channel $c p99=${p99[c]} exceeds 4", p99[c] <= 4)
            assertTrue("$label channel $c maxAbs=${maxByChannel[c]} exceeds 8", maxByChannel[c] <= 8)
        }
    }

    /** Structural alignment guard: best integer shift between AGSL and reference must be (0,0). */
    private fun assertZeroShiftPeak(label: String, agsl: Bitmap, reference: Bitmap) {
        val w = agsl.width
        val h = agsl.height
        val agslPx = IntArray(w * h).also { agsl.getPixels(it, 0, w, 0, 0, w, h) }
        val refPx = IntArray(w * h).also { reference.getPixels(it, 0, w, 0, 0, w, h) }
        val stride = 4
        val margin = 8
        val xs = ((w - 2 * margin) / stride).toInt()
        val ys = ((h - 2 * margin) / stride).toInt()
        val agslSamples = FloatArray(xs * ys)
        val refSamples = FloatArray(xs * ys)
        for (iy in 0 until ys) {
            for (ix in 0 until xs) {
                val p = (margin + iy * stride) * w + (margin + ix * stride)
                agslSamples[iy * xs + ix] = green01(agslPx[p])
                refSamples[iy * xs + ix] = green01(refPx[p])
            }
        }
        normalizeInPlace(agslSamples)
        normalizeInPlace(refSamples)
        var bestScore = Double.NEGATIVE_INFINITY
        var bestDx = Int.MAX_VALUE
        var bestDy = Int.MAX_VALUE
        for (dy in -8..8) {
            for (dx in -8..8) {
                var dot = 0.0
                for (iy in 0 until ys) {
                    val sy = iy + dy
                    if (sy < 0 || sy >= ys) continue
                    val rowBase = sy * xs
                    val refRowBase = iy * xs
                    for (ix in 0 until xs) {
                        val sx = ix + dx
                        if (sx < 0 || sx >= xs) continue
                        dot += agslSamples[rowBase + sx] * refSamples[refRowBase + ix]
                    }
                }
                if (dot > bestScore) {
                    bestScore = dot
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        println("[AgslAlign] shiftPeak label=$label dx=$bestDx dy=$bestDy score=$bestScore")
        assertEquals("$label shift peak dx", 0, bestDx)
        assertEquals("$label shift peak dy", 0, bestDy)
    }

    /** Analytic point values from constant region colors via the pinned Lab helpers. */
    private fun assertAnalyticPoints(label: String, agsl: Bitmap, mw: Int, mh: Int) {
        val px = IntArray(mw * mh).also { agsl.getPixels(it, 0, mw, 0, 0, mw, mh) }
        val fractions = listOf(
            0.10 to 0.10,
            0.90 to 0.10,
            0.10 to 0.90,
            0.90 to 0.90,
            0.50 to 0.50,
        )
        for ((fx, fy) in fractions) {
            val x = (fx * (mw - 1)).roundToInt()
            val y = (fy * (mh - 1)).roundToInt()
            val orig = floatArrayOf(sourceGray01(fx, fy), sourceGray01(fx, fy), sourceGray01(fx, fy))
            val workColor = workColor01(fx, fy)
            val expected = expectedCombine(orig, workColor)
            val actual = floatArrayOf(
                red(px[y * mw + x]) / 255f,
                green(px[y * mw + x]) / 255f,
                blue(px[y * mw + x]) / 255f,
            )
            for (c in 0 until 3) {
                val diff255 = abs(expected[c] - actual[c]) * 255f
                assertTrue(
                    "$label point($fx,$fy) channel $c off by ${"%.2f".format(diff255)}/255",
                    diff255 <= 2f,
                )
            }
        }
    }

    /** The L-layer fix discriminator: a source-ink square must land at its source fraction. */
    private fun assertInkSquareCentroid(label: String, agsl: Bitmap, mw: Int, mh: Int) {
        val (cx, cy) = centroidMatching(agsl) { r, g, b -> luminance01(r, g, b) < 80f / 255f }
        println(
            "[AgslAlign] inkCentroid label=$label fx=${"%.4f".format(cx)} fy=${"%.4f".format(cy)}",
        )
        assertTrue("$label ink centroid fx=$cx not at 0.75", abs(cx - 0.75) <= 0.01)
        assertTrue("$label ink centroid fy=$cy not at 0.50", abs(cy - 0.50) <= 0.01)
    }

    /** The color-layer guard: a work-layer magenta marker must land at its work fraction. */
    private fun assertMarkerCentroid(label: String, agsl: Bitmap, mw: Int, mh: Int) {
        val (cx, cy) = centroidMatching(agsl) { r, g, b -> r > 150 && b > 150 && g < 120 }
        println(
            "[AgslAlign] markerCentroid label=$label fx=${"%.4f".format(cx)} fy=${"%.4f".format(cy)}",
        )
        assertTrue("$label marker centroid fx=$cx not at 0.75", abs(cx - 0.75) <= 0.01)
        assertTrue("$label marker centroid fy=$cy not at 0.50", abs(cy - 0.50) <= 0.01)
    }

    // --- synthetic layers ---------------------------------------------------------------

    /** Uniform grays ≥0.45 outside one dark (≤0.12) ink square at fraction (0.75, 0.50). */
    private fun buildSource(sw: Int, sh: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.rgb(200, 200, 200)
        canvas.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), paint)
        paint.color = Color.rgb(160, 160, 160)
        canvas.drawRect(0f, 0f, (0.15f * sw), (0.15f * sh), paint)
        paint.color = Color.rgb(180, 180, 180)
        canvas.drawRect(0.85f * sw, 0f, sw.toFloat(), (0.15f * sh), paint)
        paint.color = Color.rgb(220, 220, 220)
        canvas.drawRect(0f, 0.85f * sh, (0.15f * sw), sh.toFloat(), paint)
        paint.color = Color.rgb(240, 240, 240)
        canvas.drawRect(0.85f * sw, 0.85f * sh, sw.toFloat(), sh.toFloat(), paint)
        paint.color = Color.rgb(170, 170, 170)
        canvas.drawRect(0.45f * sw, 0.45f * sh, 0.55f * sw, 0.55f * sh, paint)
        val side = 0.02f * minOf(sw, sh)
        paint.color = Color.rgb(30, 30, 30)
        canvas.drawRect(
            0.75f * sw - side / 2,
            0.50f * sh - side / 2,
            0.75f * sw + side / 2,
            0.50f * sh + side / 2,
            paint,
        )
        return bitmap
    }

    /** Four distinct-chroma quadrant colors plus a magenta marker at fraction (0.75, 0.50). */
    private fun buildWork(ww: Int, wh: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(ww, wh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.rgb(230, 0, 0)
        canvas.drawRect(0f, 0f, (0.5f * ww), (0.5f * wh), paint)
        paint.color = Color.rgb(0, 230, 0)
        canvas.drawRect(0.5f * ww, 0f, ww.toFloat(), (0.5f * wh), paint)
        paint.color = Color.rgb(0, 0, 230)
        canvas.drawRect(0f, 0.5f * wh, (0.5f * ww), wh.toFloat(), paint)
        paint.color = Color.rgb(230, 230, 0)
        canvas.drawRect(0.5f * ww, 0.5f * wh, ww.toFloat(), wh.toFloat(), paint)
        val side = 0.04f * minOf(ww, wh)
        paint.color = Color.rgb(230, 0, 230)
        canvas.drawRect(
            0.75f * ww - side / 2,
            0.50f * wh - side / 2,
            0.75f * ww + side / 2,
            0.50f * wh + side / 2,
            paint,
        )
        return bitmap
    }

    // --- numeric helpers ----------------------------------------------------------------

    /** Source gray at a merge-space fraction; matches buildSource's region layout. */
    private fun sourceGray01(fx: Double, fy: Double): Float {
        val gray = when {
            fx < 0.15 && fy < 0.15 -> 160
            fx >= 0.85 && fy < 0.15 -> 180
            fx < 0.15 && fy >= 0.85 -> 220
            fx >= 0.85 && fy >= 0.85 -> 240
            fx in 0.45..0.55 && fy in 0.45..0.55 -> 170
            abs(fx - 0.75) <= 0.01 && abs(fy - 0.50) <= 0.01 -> 30
            else -> 200
        }
        return gray / 255f
    }

    /** Work color at a merge-space fraction; matches buildWork's quadrant layout. */
    private fun workColor01(fx: Double, fy: Double): FloatArray {
        val rgb = when {
            fx < 0.5 && fy < 0.5 -> intArrayOf(230, 0, 0)
            fx >= 0.5 && fy < 0.5 -> intArrayOf(0, 230, 0)
            fx < 0.5 && fy >= 0.5 -> intArrayOf(0, 0, 230)
            abs(fx - 0.75) <= 0.02 && abs(fy - 0.50) <= 0.02 -> intArrayOf(230, 0, 230)
            else -> intArrayOf(230, 230, 0)
        }
        return floatArrayOf(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f)
    }

    /** Pinned formula expectation: a/b from work, L mixed from orig by the shading strength. */
    private fun expectedCombine(origGray01: FloatArray, workColor01: FloatArray): FloatArray {
        val origLab = LineArtMerger.rgbToLab(origGray01)
        val workLab = LineArtMerger.rgbToLab(workColor01)
        val gray = 0.299f * origGray01[0] + 0.587f * origGray01[1] + 0.114f * origGray01[2]
        val weight = INK_SHADING_STRENGTH * inkWeight(gray)
        val lab = floatArrayOf(
            origLab[0] * (1f - weight) + workLab[0] * weight,
            workLab[1],
            workLab[2],
        )
        return LineArtMerger.labToRgb(lab)
    }

    private fun inkWeight(gray: Float): Float {
        val t = ((gray - 0.12f) / 0.33f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun interleavedRgb01(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val rgb = FloatArray(px.size * 3)
        for (i in px.indices) {
            rgb[i * 3] = red(px[i]) / 255f
            rgb[i * 3 + 1] = green(px[i]) / 255f
            rgb[i * 3 + 2] = blue(px[i]) / 255f
        }
        return rgb
    }

    private fun scaleTo(source: Bitmap, width: Int, height: Int): Bitmap =
        if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }

    private fun percentile(histogram: IntArray, count: Int, quantile: Double): Int {
        var accumulated = 0
        val threshold = count * quantile
        for (v in histogram.indices) {
            accumulated += histogram[v]
            if (accumulated >= threshold) return v
        }
        return 255
    }

    private fun centroidMatching(bitmap: Bitmap, match: (Int, Int, Int) -> Boolean): Pair<Double, Double> {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = px[y * w + x]
                if (match(red(argb), green(argb), blue(argb))) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }
        assertTrue("centroid matcher found no pixels", count > 0)
        return (sumX / count / w) to (sumY / count / h)
    }

    private fun normalizeInPlace(samples: FloatArray) {
        var mean = 0.0
        for (s in samples) mean += s
        mean /= samples.size
        var norm = 0.0
        for (i in samples.indices) {
            samples[i] = (samples[i] - mean).toFloat()
            norm += samples[i] * samples[i]
        }
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) {
            for (i in samples.indices) samples[i] = (samples[i] / norm).toFloat()
        }
    }

    private fun luminance01(r: Int, g: Int, b: Int): Float =
        (0.299f * r + 0.587f * g + 0.114f * b) / 255f

    private fun green01(argb: Int): Float = ((argb shr 8) and 0xFF) / 255f

    private fun red(argb: Int): Int = (argb shr 16) and 0xFF

    private fun green(argb: Int): Int = (argb shr 8) and 0xFF

    private fun blue(argb: Int): Int = argb and 0xFF
}
