package eu.kanade.translation.processing

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Pins the bounded colored-bitmap handoff (F4): ownership transfer semantics,
 * capacity eviction recycling, poll consumption, and the processor's offer
 * gating. Bitmaps are mockk doubles — the lifecycle (who recycles) is what is
 * pinned, not pixel content.
 */
class ColoredBitmapHandoffTest {

    private fun bitmap(): Bitmap = mockk(relaxed = true)

    @Test
    fun `poll removes and returns the offered bitmap once`() {
        val handoff = ColoredBitmapHandoff()
        val bitmap = bitmap()

        handoff.offer("p1.colored.a.jpg", bitmap)

        handoff.poll("p1.colored.a.jpg") shouldBe bitmap
        handoff.poll("p1.colored.a.jpg") shouldBe null
    }

    @Test
    fun `capacity eviction recycles the oldest unconsumed entry`() {
        val handoff = ColoredBitmapHandoff(capacity = 2)
        val oldest = bitmap()
        val middle = bitmap()
        val newest = bitmap()

        handoff.offer("a.jpg", oldest)
        handoff.offer("b.jpg", middle)
        handoff.offer("c.jpg", newest)

        verify { oldest.recycle() }
        verify(exactly = 0) { middle.recycle() }
        handoff.poll("b.jpg") shouldBe middle
        handoff.poll("c.jpg") shouldBe newest
    }

    @Test
    fun `re-offering the same name recycles the previous entry`() {
        val handoff = ColoredBitmapHandoff()
        val first = bitmap()
        val second = bitmap()

        handoff.offer("a.jpg", first)
        handoff.offer("a.jpg", second)

        verify { first.recycle() }
        handoff.poll("a.jpg") shouldBe second
    }

    @Test
    fun `clear recycles every unconsumed bitmap`() {
        val handoff = ColoredBitmapHandoff()
        val first = bitmap()
        val second = bitmap()
        handoff.offer("a.jpg", first)
        handoff.offer("b.jpg", second)

        handoff.clear()

        verify { first.recycle() }
        verify { second.recycle() }
        handoff.poll("a.jpg") shouldBe null
    }

    @Test
    fun `poll after clear returns nothing`() {
        val handoff = ColoredBitmapHandoff()
        val bitmap = bitmap()
        handoff.offer("a.jpg", bitmap)
        handoff.clear()
        handoff.poll("a.jpg") shouldBe null
    }
}

/** Offer gating on the processor seam: only committed, named, READY colored bitmaps move. */
class HandOffColoredBitmapForDisplayTest {

    private val context: Context = mockk(relaxed = true)

    private fun processor(handoff: ColoredBitmapHandoff?): TranslationProcessor =
        TranslationProcessor(context, coloredBitmapHandoff = handoff)

    private fun page(
        coloringStatus: String,
        coloredImageName: String?,
        coloredBitmap: Bitmap?,
    ): PageTranslation = PageTranslation(sourceFileName = "p1.jpg").apply {
        this.coloringStatus = coloringStatus
        this.coloredImageName = coloredImageName
        this.coloredBitmap = coloredBitmap
    }

    @Test
    fun `ready named commit transfers ownership out of the page`() {
        val handoff = ColoredBitmapHandoff()
        val bitmap = mockk<Bitmap>(relaxed = true)
        val page = page(StageStatus.READY, "p1.colored.v1.jpg", bitmap)

        processor(handoff).handOffColoredBitmapForDisplay(page)

        handoff.poll("p1.colored.v1.jpg") shouldBe bitmap
        page.coloredBitmap shouldBe null
        // releasePageBitmaps must not recycle a handed-off bitmap.
        processor(handoff).releasePageBitmaps(page)
        verify(exactly = 0) { bitmap.recycle() }
    }

    @Test
    fun `unnamed or non-ready commits keep the pipeline lifecycle`() {
        val handoff = ColoredBitmapHandoff()
        val unnamedBitmap = mockk<Bitmap>(relaxed = true)
        val failedBitmap = mockk<Bitmap>(relaxed = true)
        val unnamed = page(StageStatus.READY, null, unnamedBitmap)
        val failed = page(StageStatus.FAILED, "p1.colored.v1.jpg", failedBitmap)
        val bitmapless = page(StageStatus.READY, "p1.colored.v1.jpg", null)

        processor(handoff).handOffColoredBitmapForDisplay(unnamed)
        processor(handoff).handOffColoredBitmapForDisplay(failed)
        processor(handoff).handOffColoredBitmapForDisplay(bitmapless)

        handoff.poll("p1.colored.v1.jpg") shouldBe null
        // No offer happened: the pipeline still owns these bitmaps.
        unnamed.coloredBitmap shouldBe unnamedBitmap
        failed.coloredBitmap shouldBe failedBitmap
    }

    @Test
    fun `no handoff attached is a no-op`() {
        val bitmap = mockk<Bitmap>(relaxed = true)
        val page = page(StageStatus.READY, "p1.colored.v1.jpg", bitmap)

        processor(null).handOffColoredBitmapForDisplay(page)

        page.coloredBitmap shouldBe bitmap
        verify(exactly = 0) { bitmap.recycle() }
    }
}
