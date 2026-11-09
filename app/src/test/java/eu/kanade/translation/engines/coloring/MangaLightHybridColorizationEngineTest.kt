package eu.kanade.translation.engines.coloring

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaLightHybridColorizationEngineTest {
    @Test
    fun `SAM channel layout is converted for generator input`() {
        val input = FloatArray(256 * 64 * 64)
        input[(7 * 64 + 3) * 64 + 5] = 0.75f
        val result = MangaLightHybridColorizationEngine.samLevel0ToNhwc(input)
        result[(3 * 64 + 5) * 256 + 7] shouldBe 0.75f
        result.count { it != 0f } shouldBe 1
    }

    @Test
    fun `SAM resize preserves edges and interpolates luma into RGB`() {
        val luma = FloatArray(512 * 512)
        luma[1] = 1f
        luma[512] = 0.5f
        val result = MangaLightHybridColorizationEngine.samRgb(luma)
        result[0] shouldBe 0f
        result[3] shouldBe 0.25f
        result[1024 * 3] shouldBe 0.125f
        result[1024 * 3 + 1] shouldBe 0.125f
        result[1024 * 3 + 2] shouldBe 0.125f
    }
}
