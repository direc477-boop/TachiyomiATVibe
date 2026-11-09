package eu.kanade.translation.engines.coloring

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.ColorizationModel

class ColorizationModelTest {

    @Test
    fun `every model round-trips through its pref value`() {
        ColorizationModel.entries.forEach { model ->
            ColorizationModel.fromPrefOrNull(model.prefValue) shouldBe model
            ColorizationModel.fromPrefOrNull(model.prefValue.lowercase()) shouldBe model
        }
    }

    @Test
    fun `hybrid route resolves and unknown values resolve to null`() {
        ColorizationModel.fromPrefOrNull("MANGA_LIGHT_SAM_NPU_GPU") shouldBe
            ColorizationModel.MANGA_LIGHT_SAM_NPU_GPU
        ColorizationModel.fromPrefOrNull("NONSENSE") shouldBe null
        ColorizationModel.DEFAULT shouldBe ColorizationModel.MANGA_LIGHT_SAM_NPU_GPU
    }
}
