package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.displayImageName
import eu.kanade.translation.model.isColorizeOnlyComplete
import eu.kanade.translation.model.isTranslationDisplayReady
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ReaderTranslationDisplayDecisionTest {

    private fun colorizeOnlyTranslation(
        coloredName: String = "p0_colored.jpg",
        status: String = StageStatus.READY,
    ) = PageTranslation(
        coloredImageName = coloredName,
        coloredFromOriginal = true,
        ocrStatus = StageStatus.SKIPPED,
        translationStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
        originalImageFallback = true,
        coloringStatus = status,
    )

    @Test
    fun `store-complete page outside warm window resolves to attach and show when visible`() {
        val page = ReaderPage(index = 5, url = "page-5")
        page.translation = colorizeOnlyTranslation()

        val translation = page.translation!!
        translation.isColorizeOnlyComplete shouldBe true

        val colorizationEnabled = true
        val displayImage = translation.displayImageName(colorizationEnabled)
        displayImage shouldBe "p0_colored.jpg"

        // Simulated viewport attachment
        page.translatedStream = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        val translationEnabled = true
        if (!page.translationToggled && translationEnabled) {
            val isDisplayReady = translation.isColorizeOnlyComplete || translation.isTranslationDisplayReady
            if (page.translatedStream != null || isDisplayReady) {
                page.showTranslatedImage = true
            }
        }

        page.showTranslatedImage shouldBe true
        page.stream shouldNotBe null
        page.stream!!.invoke().read() shouldBe 1
    }

    @Test
    fun `eviction keeps stream bytes bounded but preserves the display decision`() {
        val page = ReaderPage(index = 0, url = "page-0")
        page.translation = colorizeOnlyTranslation()
        page.translatedStream = { ByteArrayInputStream(byteArrayOf(1)) }
        page.originalStream = { ByteArrayInputStream(byteArrayOf(0)) }
        page.showTranslatedImage = true

        // Working-set eviction sweep for cold page outside eviction radius
        // Stream bytes are cleared to bound memory, but display decision is NOT dropped
        page.translatedStream = null

        page.showTranslatedImage shouldBe true
        // Fallback to original stream while bytes are evicted
        page.stream!!.invoke().read() shouldBe 0

        // Re-entering viewport re-attaches stream deterministically
        page.translatedStream = { ByteArrayInputStream(byteArrayOf(2)) }
        page.showTranslatedImage shouldBe true
        page.stream!!.invoke().read() shouldBe 2
    }

    @Test
    fun `manual toggle-off wins over automatic display readiness`() {
        val page = ReaderPage(index = 1, url = "page-1")
        page.translation = colorizeOnlyTranslation()
        page.translatedStream = { ByteArrayInputStream(byteArrayOf(1)) }
        page.originalStream = { ByteArrayInputStream(byteArrayOf(0)) }

        // User manually toggles off translation on this page
        page.translationToggled = true
        page.showTranslatedImage = false

        // Automatic display decision logic
        val translation = page.translation!!
        val isDisplayReady = translation.isColorizeOnlyComplete || translation.isTranslationDisplayReady
        isDisplayReady shouldBe true

        if (!page.translationToggled) {
            page.showTranslatedImage = true
        }

        // Manual toggle-off is preserved
        page.showTranslatedImage shouldBe false
        page.stream!!.invoke().read() shouldBe 0
    }

    @Test
    fun `holder refresh correctly triggers decode when image ready and short-circuits when unchanged`() {
        val page = ReaderPage(index = 2, url = "page-2")
        val translation = colorizeOnlyTranslation("p2_colored.jpg")
        page.translation = translation
        page.translatedStream = { ByteArrayInputStream(byteArrayOf(5)) }

        val showTranslations = true
        val colorizationEnabled = true
        val newName: String? = page.translation?.displayImageName(colorizationEnabled)
        val hasDisplayImage = newName != null
        val isDisplayReady = page.translation?.isColorizeOnlyComplete == true

        var lastShownImageName: String? = null
        page.showTranslatedImage = false

        // Initial check: currently showing original, new colored image is ready
        val wantTranslated1 = showTranslations && (hasDisplayImage || isDisplayReady)
        wantTranslated1 shouldBe true

        val alreadyShowing1 = if (wantTranslated1) {
            newName != null && page.showTranslatedImage && newName == lastShownImageName
        } else {
            !page.showTranslatedImage && lastShownImageName == null
        }
        alreadyShowing1 shouldBe false

        // Simulated setImage decode completes
        page.showTranslatedImage = true
        lastShownImageName = newName

        // Subsequent check: already showing correct colored image
        val alreadyShowing2 = if (wantTranslated1) {
            newName != null && page.showTranslatedImage && newName == lastShownImageName
        } else {
            !page.showTranslatedImage && lastShownImageName == null
        }
        alreadyShowing2 shouldBe true
    }
}
