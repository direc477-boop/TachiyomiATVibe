package eu.kanade.translation.engines.coloring

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaColorizationLetterboxTest {

    @Test
    fun `fits a tall page without changing its aspect ratio`() {
        val content = MangaColorizationLetterbox.fit(512, 1024, targetWidth = 512, targetHeight = 704)

        content.width shouldBe 352
        content.height shouldBe 704
        content.left shouldBe 80
        content.top shouldBe 0
    }

    @Test
    fun `fits a landscape page without changing its aspect ratio`() {
        val content = MangaColorizationLetterbox.fit(1024, 512, targetWidth = 512, targetHeight = 704)

        content.width shouldBe 512
        content.height shouldBe 256
        content.left shouldBe 0
        content.top shouldBe 224
    }

    @Test
    fun `uses the full graph canvas for matching page dimensions`() {
        val content = MangaColorizationLetterbox.fit(512, 704, targetWidth = 512, targetHeight = 704)

        content.width shouldBe 512
        content.height shouldBe 704
        content.left shouldBe 0
        content.top shouldBe 0
    }

    @Test
    fun `flags extreme aspect ratios for CPU inference`() {
        MangaColorizationLetterbox.fit(512, 2048, targetWidth = 512, targetHeight = 704)
            .requiresCpuFallback shouldBe true
        MangaColorizationLetterbox.fit(2048, 512, targetWidth = 512, targetHeight = 704)
            .requiresCpuFallback shouldBe true
        MangaColorizationLetterbox.fit(512, 1024, targetWidth = 512, targetHeight = 704)
            .requiresCpuFallback shouldBe false
    }
}
