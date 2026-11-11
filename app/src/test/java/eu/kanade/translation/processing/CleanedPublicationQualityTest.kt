package eu.kanade.translation.processing

import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.translation.engines.inpainting.InpaintingMode
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Pins the published-image JPEG quality (F6): cleaned/colored files encode at
 * [CLEANED_JPEG_QUALITY]. Only newly written files get the smaller size —
 * existing artifacts keep their original bytes.
 */
class CleanedPublicationQualityTest {

    @Test
    fun `the pinned quality is 80`() {
        CLEANED_JPEG_QUALITY shouldBe 80
    }

    @Test
    fun `writeVerifiedCleanedImage encodes with the pinned quality`() {
        val directory = mockk<UniFile>()
        val imageFile = mockk<UniFile>()
        val identityFile = mockk<UniFile>()
        every { directory.findFile(any()) } returns null
        every { directory.createFile(any()) } returnsMany listOf(imageFile, identityFile)
        every { imageFile.openOutputStream() } returns ByteArrayOutputStream()
        every { imageFile.exists() } returns true
        every { imageFile.length() } returns 1L
        every { identityFile.openOutputStream() } returns ByteArrayOutputStream()
        every { identityFile.exists() } returns true
        every { identityFile.length() } returns 1L
        val bitmap = mockk<Bitmap>()
        val quality = slot<Int>()
        every { bitmap.compress(any(), capture(quality), any()) } returns true

        CleanedPublication(
            provider = mockk(),
            currentInpaintingMode = { InpaintingMode.BALANCE },
        ).writeVerifiedCleanedImage(directory, "p1", bitmap)

        quality.captured shouldBe CLEANED_JPEG_QUALITY
        verify(exactly = 1) { bitmap.compress(any(), any(), any()) }
    }
}
