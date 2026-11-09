package eu.kanade.presentation.translation

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.TranslationPreferences

/**
 * The colorization selector's option list is the single source shared by the
 * reader sheet and the settings screens: it must list both surviving engines,
 * and a W8A8 selection must persist and read back unchanged.
 */
class ColorizationModelOptionsContractTest {

    @Test
    fun `option list offers every selectable engine in enum order`() {
        colorizationModelValues shouldBe ColorizationModel.entries.toList()
    }

    @Test
    fun `both surviving engines are visible choices`() {
        colorizationModelValues shouldContain ColorizationModel.MANGA_COLORIZATION_V2_NPU_W8A8
        colorizationModelValues shouldContain ColorizationModel.MANGA_LIGHT_SAM_NPU_GPU
    }

    @Test
    fun `w8a8 selection persists across fresh facades`() {
        val firstFacade = TranslationPreferences(InMemoryPreferenceStore())
        val modelPref = firstFacade.translationColorizationModel()
        modelPref.set(ColorizationModel.MANGA_COLORIZATION_V2_NPU_W8A8)

        val reopened = TranslationPreferences(persistedEnumStoreWith(modelPref))
        reopened.translationColorizationModel().get() shouldBe ColorizationModel.MANGA_COLORIZATION_V2_NPU_W8A8
    }

    /** Store whose durable contents hold [pref]'s flushed value. */
    private fun persistedEnumStoreWith(pref: Preference<ColorizationModel>): InMemoryPreferenceStore =
        InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    pref.key(),
                    pref.get(),
                    pref.defaultValue(),
                ),
            ),
        )
}
