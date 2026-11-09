package eu.kanade.translation.engines.coloring

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Pure cap math shared by the AGSL and CPU combine paths (aspect preserved, 1.5 MP bound). */
class MangaColorizationMergeDimensionsTest {

    @Test
    fun `pages under the cap keep their size`() {
        MangaColorizationImageProcessing.mergeDimensions(512, 704) shouldBe (512 to 704)
        MangaColorizationImageProcessing.mergeDimensions(1400, 1069) shouldBe (1400 to 1069)
    }

    @Test
    fun `oversized pages shrink to the cap preserving aspect`() {
        MangaColorizationImageProcessing.mergeDimensions(2000, 3000) shouldBe (1000 to 1500)
        MangaColorizationImageProcessing.mergeDimensions(3000, 2000) shouldBe (1500 to 1000)
    }

    @Test
    fun `square pages stay square through the cap`() {
        val square = MangaColorizationImageProcessing.mergeDimensions(4096, 4096)
        square.first shouldBe square.second
    }

    @Test
    fun `non-positive dimensions are rejected`() {
        assertThrows<IllegalArgumentException> { MangaColorizationImageProcessing.mergeDimensions(0, 100) }
        assertThrows<IllegalArgumentException> { MangaColorizationImageProcessing.mergeDimensions(100, -1) }
    }
}
