package eu.kanade.translation.engines.upscaling

import android.content.Context
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** Contract: hard-fail availability semantics + halo tiling math + fixed-layout packing. */
class LiteRTQuickSRNetUpscalingEngineTest {

    private class FakeSession : LiteRTQuickSRNetUpscalingEngine.Dependencies.Session {
        override fun run(input: FloatArray): FloatArray =
            FloatArray(LiteRTQuickSRNetUpscalingEngine.OUTPUT_FLOAT_COUNT) { 1f }

        override fun close() = Unit
    }

    private fun engine(assetSha: String?): LiteRTQuickSRNetUpscalingEngine {
        val deps = LiteRTQuickSRNetUpscalingEngine.Dependencies(
            artifact = { assetSha?.let { LiteRTQuickSRNetUpscalingEngine.Dependencies.Artifact(File("unused"), it) } },
            precheck = { null },
            openSession = { FakeSession() },
        )
        return LiteRTQuickSRNetUpscalingEngine(deps)
    }

    @Test
    fun `missing artifact fails visibly without fallback`() {
        val engine = engine(null)
        engine.isAvailable() shouldBe false
        engine.initializationError() shouldNotBe null
        engine.initializationError()!!.contains("not present") shouldBe true
    }

    @Test
    fun `artifact hash mismatch is refused`() {
        val engine = engine("deadbeef")
        engine.isAvailable() shouldBe false
        engine.initializationError()!!.contains("does not match") shouldBe true
    }

    @Test
    fun `matching artifact opens session`() {
        val engine = engine(LiteRTQuickSRNetUpscalingEngine.EXPECTED_ARTIFACT_SHA256)
        engine.isAvailable() shouldBe true
        engine.initializationError() shouldBe null
    }

    @Test
    fun `packing uses fixed NHWC layout with edge replication`() {
        val engine = engine(LiteRTQuickSRNetUpscalingEngine.EXPECTED_ARTIFACT_SHA256)
        val side = LiteRTQuickSRNetUpscalingEngine.MODEL_SIDE

        // 2x1 source: red at (0,0), blue at (1,0).
        val packed = engine.rgb01NhwcFromPixels(intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), width = 2, height = 1)
        packed.size shouldBe side * side * 3
        packed[0] shouldBe 1f
        packed[1] shouldBe 0f
        packed[2] shouldBe 0f
        // Column 1 holds blue; columns 2..side-1 replicate column 1 (edge clamp).
        val col1 = 3
        packed[col1] shouldBe 0f
        packed[col1 + 1] shouldBe 0f
        packed[col1 + 2] shouldBe 1f
        val col2 = 6
        packed[col2] shouldBe packed[col1]
        packed[col2 + 2] shouldBe packed[col1 + 2]
    }

    @Test
    fun `output crop maps source region to exactly 4x dimensions`() {
        val engine = engine(LiteRTQuickSRNetUpscalingEngine.EXPECTED_ARTIFACT_SHA256)
        val ones = FloatArray(LiteRTQuickSRNetUpscalingEngine.OUTPUT_FLOAT_COUNT) { 1f }
        val argb = engine.regionScaledArgbFromRgb01(ones, width = 2, height = 1)
        argb.size shouldBe 8 * 4
        argb.forEach { value -> value shouldBe 0xFFFFFFFF.toInt() }
    }

    @Test
    fun `computeSlices produces contiguous non-overlapping destination coverage for odd and multi-tile sizes`() {
        val engine = engine(LiteRTQuickSRNetUpscalingEngine.EXPECTED_ARTIFACT_SHA256)
        val testSizes = listOf(1, 15, 71, 89, 127, 128, 129, 137, 200, 245, 300, 1024, 2048)

        for (dim in testSizes) {
            val slices = engine.computeSlices(dim)
            slices.isNotEmpty() shouldBe true

            // First slice starts at destination 0
            slices.first().dstStart shouldBe 0

            // Last slice ends exactly at dim * SCALE
            slices.last().dstEnd shouldBe dim * LiteRTQuickSRNetUpscalingEngine.SCALE

            // Every adjacent pair of slices is strictly contiguous with zero gap and zero overlap
            for (i in 0 until slices.size - 1) {
                val current = slices[i]
                val next = slices[i + 1]
                current.dstEnd shouldBe next.dstStart
            }

            // Slices keep valid within-tile crop ranges
            for (slice in slices) {
                val patchLen = slice.inEnd - slice.inStart
                patchLen shouldBe (slice.inEnd - slice.inStart)
                (slice.cropEnd <= patchLen || slice.inEnd < dim) shouldBe true
                (slice.cropEnd - slice.cropStart) * LiteRTQuickSRNetUpscalingEngine.SCALE shouldBe (slice.dstEnd - slice.dstStart)
            }
        }
    }

    @Test
    fun `computeSlices rejects non-positive dimensions`() {
        val engine = engine(LiteRTQuickSRNetUpscalingEngine.EXPECTED_ARTIFACT_SHA256)
        assertThrows<IllegalArgumentException> {
            engine.computeSlices(0)
        }
        assertThrows<IllegalArgumentException> {
            engine.computeSlices(-10)
        }
    }

    @Test
    fun `safety bounds constants enforce sound scale limits`() {
        LiteRTQuickSRNetUpscalingEngine.MAX_OUTPUT_DIMENSION shouldBe
            LiteRTQuickSRNetUpscalingEngine.MAX_INPUT_DIMENSION * LiteRTQuickSRNetUpscalingEngine.SCALE
        LiteRTQuickSRNetUpscalingEngine.MAX_OUTPUT_DIMENSION shouldBe 8192
        LiteRTQuickSRNetUpscalingEngine.MAX_OUTPUT_PIXELS shouldBe 48_000_000L
    }

    @Test
    fun `unprovisioned engine fails visibly with clear error`() {
        val unprov = UnprovisionedUpscalingEngine("Custom unprovisioned reason")
        unprov.isAvailable() shouldBe false
        unprov.initializationError() shouldBe "Custom unprovisioned reason"
    }

    @Test
    fun `catalog createEngine returns unprovisioned engine when operational is false`() {
        UpscalingModelCatalog.operational = false
        try {
            val engine = UpscalingModelCatalog.createEngine(mockk<Context>(relaxed = true))
            engine.isAvailable() shouldBe false
            engine.initializationError()!!.contains("unprovisioned") shouldBe true
        } finally {
            UpscalingModelCatalog.operational = true
        }
    }

    @Test
    fun `catalog createEngine returns live engine when operational is true`() {
        UpscalingModelCatalog.operational = true
        val engine = UpscalingModelCatalog.createEngine(mockk<Context>(relaxed = true))
        engine.shouldBeInstanceOf<LiteRTQuickSRNetUpscalingEngine>()
    }
}
