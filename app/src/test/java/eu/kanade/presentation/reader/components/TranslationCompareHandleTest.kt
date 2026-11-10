package eu.kanade.presentation.reader.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationCompareHandleTest {

    @Test
    fun `sub-slop jitter followed by release toggles menu without dragging`() {
        val session = FloaterDragSession(startX = 1f, startY = 0.5f, touchSlopPx = 24f)

        val started1 = session.onMove(Offset(8f, 0f), 1f, 0.5f, 800f, 1600f)
        val started2 = session.onMove(Offset(-8f, 0f), 1f, 0.5f, 800f, 1600f)
        val started3 = session.onMove(Offset(4f, 4f), 1f, 0.5f, 800f, 1600f)

        assertFalse(started1 || started2 || started3)
        assertFalse(session.isDragging)
        assertEquals(1f, session.xFraction)
        assertEquals(0.5f, session.yFraction)
        assertEquals(FloaterReleaseAction.TOGGLE_MENU, session.onEnd(releasedNormally = true))
    }

    @Test
    fun `cancelled pointer before drag commits neither drag nor menu toggle`() {
        val session = FloaterDragSession(startX = 0f, startY = 0.3f, touchSlopPx = 24f)

        session.onMove(Offset(6f, 6f), 0f, 0.3f, 800f, 1600f)

        assertFalse(session.isDragging)
        assertEquals(FloaterReleaseAction.NONE, session.onEnd(releasedNormally = false))
    }

    @Test
    fun `movement past touch slop drags smoothly and commits without opening menu`() {
        val session = FloaterDragSession(startX = 1f, startY = 0.5f, touchSlopPx = 24f)

        val started1 = session.onMove(Offset(-20f, 0f), 1f, 0.5f, 800f, 1600f)
        val started2 = session.onMove(Offset(-20f, 160f), 1f, 0.5f, 800f, 1600f)
        val started3 = session.onMove(Offset(-180f, 160f), session.xFraction, session.yFraction, 800f, 1600f)

        assertFalse(started1)
        assertTrue(started2)
        assertFalse(started3)
        assertTrue(session.isDragging)
        assertEquals(0.75f, session.xFraction, 0.0001f)
        assertEquals(0.7f, session.yFraction, 0.0001f)
        assertEquals(FloaterReleaseAction.COMMIT_DRAG, session.onEnd(releasedNormally = true))
    }

    @Test
    fun `drag fraction clamps at screen bounds`() {
        val session = FloaterDragSession(startX = 0.95f, startY = 0.05f, touchSlopPx = 10f)

        session.onMove(Offset(200f, -500f), 0.95f, 0.05f, 800f, 1600f)

        assertEquals(1f, session.xFraction)
        assertEquals(0f, session.yFraction)
    }

    @Test
    fun `unmeasured menu offset clears handle instead of spawning at origin`() {
        val rightDockedOffset = computeFloaterMenuOffset(
            containerWidthPx = 1080,
            containerHeightPx = 2200,
            menuSizePx = IntSize.Zero,
            fallbackWidthPx = 540f,
            fallbackHeightPx = 720f,
            horizontalInsetPx = 48f,
            topInsetPx = 144f,
            bottomMarginPx = 48f,
            handleSizePx = 120f,
            menuSpacingPx = 18f,
            xFraction = 1f,
            yFraction = 0.5f,
            usableWidth = 864f,
            usableHeight = 1888f,
        )
        // Right-docked handle starts at 48 + 864 = 912; menu must open to its left.
        assertTrue(rightDockedOffset.x + 540 <= 912 - 18)

        val leftDockedOffset = computeFloaterMenuOffset(
            containerWidthPx = 1080,
            containerHeightPx = 2200,
            menuSizePx = IntSize.Zero,
            fallbackWidthPx = 540f,
            fallbackHeightPx = 720f,
            horizontalInsetPx = 48f,
            topInsetPx = 144f,
            bottomMarginPx = 48f,
            handleSizePx = 120f,
            menuSpacingPx = 18f,
            xFraction = 0f,
            yFraction = 0.5f,
            usableWidth = 864f,
            usableHeight = 1888f,
        )
        // Left-docked handle spans 48..168; menu must open to its right.
        assertTrue(leftDockedOffset.x >= 48 + 120 + 18)
    }

    @Test
    fun `menu offset clamps within top and bottom reserves`() {
        val topClamped = computeFloaterMenuOffset(
            containerWidthPx = 1080,
            containerHeightPx = 2200,
            menuSizePx = IntSize(500, 600),
            fallbackWidthPx = 540f,
            fallbackHeightPx = 720f,
            horizontalInsetPx = 48f,
            topInsetPx = 144f,
            bottomMarginPx = 360f,
            handleSizePx = 120f,
            menuSpacingPx = 18f,
            xFraction = 1f,
            yFraction = 0f,
            usableWidth = 864f,
            usableHeight = 1576f,
        )
        assertEquals(144, topClamped.y)

        val bottomClamped = computeFloaterMenuOffset(
            containerWidthPx = 1080,
            containerHeightPx = 2200,
            menuSizePx = IntSize(500, 600),
            fallbackWidthPx = 540f,
            fallbackHeightPx = 720f,
            horizontalInsetPx = 48f,
            topInsetPx = 144f,
            bottomMarginPx = 360f,
            handleSizePx = 120f,
            menuSpacingPx = 18f,
            xFraction = 1f,
            yFraction = 1f,
            usableWidth = 864f,
            usableHeight = 1576f,
        )
        assertEquals(2200 - 360 - 600, bottomClamped.y)
    }
}
