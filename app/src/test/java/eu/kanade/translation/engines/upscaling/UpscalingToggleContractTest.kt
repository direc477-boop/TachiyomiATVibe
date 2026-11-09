package eu.kanade.translation.engines.upscaling

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.TranslationPreferences

/**
 * QuickSRNetLarge upscaling ships as an opt-in gate: the preference defaults OFF
 * and persists the user's choice, and the catalog gate reads operational when
 * enabled for experimental testing.
 */
class UpscalingToggleContractTest {

    @Test
    fun `upscaling defaults to off`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())

        prefs.translationUpscalingEnabled().get() shouldBe false
    }

    @Test
    fun `upscaling toggle persists across fresh facades`() {
        val firstFacade = TranslationPreferences(InMemoryPreferenceStore())
        val gate = firstFacade.translationUpscalingEnabled()
        gate.set(true)

        val reopened = TranslationPreferences(persistedStoreWith(gate))
        reopened.translationUpscalingEnabled().get() shouldBe true
    }

    @Test
    fun `toggle can be switched back off`() {
        val prefs = TranslationPreferences(InMemoryPreferenceStore())
        val gate = prefs.translationUpscalingEnabled()
        gate.set(true)
        TranslationPreferences(persistedStoreWith(gate)).translationUpscalingEnabled().get() shouldBe true

        gate.set(false)
        TranslationPreferences(persistedStoreWith(gate)).translationUpscalingEnabled().get() shouldBe false
    }

    @Test
    fun `catalog gate is operational for experimental testing`() {
        UpscalingModelCatalog.operational shouldBe true
    }

    /** Store whose durable contents hold [flag]'s flushed value. */
    private fun persistedStoreWith(flag: Preference<Boolean>): InMemoryPreferenceStore =
        InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    flag.key(),
                    flag.get(),
                    flag.defaultValue(),
                ),
            ),
        )
}
