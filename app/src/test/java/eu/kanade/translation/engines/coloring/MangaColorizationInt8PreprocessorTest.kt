package eu.kanade.translation.engines.coloring

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.ColorizationQuality

class MangaColorizationInt8PreprocessorTest {

    @Test
    fun qualityPresetsKeepAspectAndPadToMultiplesOf32() {
        val sourceWidth = 1125
        val sourceHeight = 1600
        val sourceRatio = sourceWidth.toDouble() / sourceHeight

        ColorizationQuality.entries.forEach { quality ->
            val geometry = MangaColorizationInt8Preprocessor.geometry(sourceWidth, sourceHeight, quality)
            val contentRatio = geometry.contentWidth.toDouble() / geometry.contentHeight

            (kotlin.math.abs(contentRatio - sourceRatio) < 0.002) shouldBe true
            (geometry.paddedWidth % 32) shouldBe 0
            (geometry.paddedHeight % 32) shouldBe 0
            (geometry.paddedWidth >= geometry.contentWidth) shouldBe true
            (geometry.paddedHeight >= geometry.contentHeight) shouldBe true
            (geometry.paddedWidth - geometry.contentWidth < 32) shouldBe true
            (geometry.paddedHeight - geometry.contentHeight < 32) shouldBe true
        }
    }

    @Test
    fun inputPackingUsesWhitePaddingAndZeroHintPlanes() {
        val geometry = MangaColorizationInt8Geometry(
            contentWidth = 2,
            contentHeight = 2,
            paddedWidth = 32,
            paddedHeight = 32,
        )
        val planeSize = geometry.paddedWidth * geometry.paddedHeight
        val input = MangaColorizationInt8Preprocessor.packGrayPlane(
            contentGray = floatArrayOf(0f, 0.25f, 0.5f, 0.75f),
            geometry = geometry,
        )

        input[0] shouldBe 0f
        input[1] shouldBe 0.25f
        input[geometry.paddedWidth] shouldBe 0.5f
        input[geometry.paddedWidth + 1] shouldBe 0.75f
        input[2] shouldBe 1f
        input[planeSize] shouldBe 0f
        for (channel in 1 until 5) {
            input.copyOfRange(channel * planeSize, (channel + 1) * planeSize).all { it == 0f } shouldBe true
        }
    }

    @Test
    fun outputCropRemovesBottomAndRightPaddingInPlanarOrder() {
        val geometry = MangaColorizationInt8Geometry(
            contentWidth = 2,
            contentHeight = 2,
            paddedWidth = 32,
            paddedHeight = 32,
        )
        val planeSize = geometry.paddedWidth * geometry.paddedHeight
        val output = FloatArray(3 * planeSize)
        for (channel in 0 until 3) {
            val base = channel * 4f
            output[channel * planeSize] = base + 1f
            output[channel * planeSize + 1] = base + 2f
            output[channel * planeSize + geometry.paddedWidth] = base + 3f
            output[channel * planeSize + geometry.paddedWidth + 1] = base + 4f
        }

        MangaColorizationInt8Preprocessor.cropOutput(output, geometry).toList() shouldBe
            listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f)
    }
}
