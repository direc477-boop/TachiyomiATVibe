package eu.kanade.presentation.reader.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

// Idle auto-hide: after this many ms with the menu collapsed and no
// interaction, the floater dims toward its docked edge.
private const val IDLE_DELAY_MS = 2500L
private const val IDLE_ALPHA = 0.38f

// Shared by every position animation so an interrupted snap restarts with the
// same easing instead of fighting another spec.
private val DockSnapSpring = spring<Float>(stiffness = 380f)

/**
 * Draggable translator floater (chat-head style) for quick original/translated
 * comparison on the CURRENT page.
 *
 * Gestures on the handle: tap toggles the menu, while holding or dragging moves the
 * handle smoothly with haptic feedback. Edge insets prevent getting stuck in bezels.
 *
 * The two [Animatable] fractions are the single source of truth: drag deltas
 * are applied with ordered [Animatable.snapTo] calls and release snaps with
 * [DockSnapSpring], so motion is continuous across grab/release and menu
 * open/close. The menu is a positioned sibling of the handle, offset and
 * clamped independently to stay inside the container, so expanding it never
 * moves the handle. Release snaps to the nearest left/right edge; the docked
 * side and normalized vertical position are persisted by the caller ONLY at
 * drag end.
 */
@Composable
fun TranslationCompareHandle(
    visible: Boolean,
    hasTranslation: Boolean,
    showingTranslated: Boolean,
    autoTranslate: Boolean,
    onSelectOriginal: () -> Unit,
    onSelectTranslated: () -> Unit,
    onToggleAutoTranslate: () -> Unit,
    onOpenSettings: () -> Unit,
    onColorizeChapter: () -> Unit,
    onColorizePage: () -> Unit,
    onPeekChange: (Boolean) -> Unit = {},
    idleFadeEnabled: Boolean,
    dockEnd: Boolean,
    onDockChange: (Boolean) -> Unit,
    verticalFraction: Float,
    onPositionChange: (Float) -> Unit,
    bottomReservePx: Float = 0f,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    var containerWidthPx by remember { mutableIntStateOf(0) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var menuSizePx by remember { mutableStateOf(IntSize.Zero) }
    var expanded by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var isIdle by remember { mutableStateOf(false) }

    val handleSizePx = with(density) { 40.dp.toPx() }
    val menuSpacingPx = with(density) { 6.dp.toPx() }

    // Normalized position, animated. x: 0 = start edge, 1 = end edge;
    // y: 0..1 top-to-bottom. Read only inside offset lambdas, so drag
    // frames invalidate layout without recomposing the reader.
    val xFraction = remember { Animatable(if (dockEnd) 1f else 0f) }
    val yFraction = remember { Animatable(verticalFraction.coerceIn(0f, 1f)) }

    // Follow preference changes (settings resets, dock-side flips) while idle.
    LaunchedEffect(dockEnd) {
        if (!dragging) xFraction.animateTo(if (dockEnd) 1f else 0f, DockSnapSpring)
    }
    LaunchedEffect(verticalFraction) {
        val clamped = verticalFraction.coerceIn(0f, 1f)
        if (!dragging && abs(yFraction.value - clamped) > 0.005f) {
            yFraction.animateTo(clamped, DockSnapSpring)
        }
    }

    // Idle timer: armed whenever the menu is collapsed and no drag is active.
    LaunchedEffect(expanded, dragging, idleFadeEnabled) {
        if (!expanded && !dragging && idleFadeEnabled) {
            delay(IDLE_DELAY_MS)
            isIdle = true
        } else {
            isIdle = false
        }
    }

    val handleAlpha by animateFloatAsState(
        targetValue = if (isIdle && !expanded) IDLE_ALPHA else 1f,
        animationSpec = tween(200),
        label = "floater-alpha",
    )

    Box(
        modifier = modifier.onSizeChanged {
            containerWidthPx = it.width
            containerHeightPx = it.height
        },
    ) {
        val horizontalInsetPx = with(density) { 16.dp.toPx() }
        val topInsetPx = with(density) { 48.dp.toPx() }
        val bottomMarginPx = with(density) { 16.dp.toPx() } + bottomReservePx
        val defaultMenuWidthPx = with(density) { 180.dp.toPx() }
        val defaultMenuHeightPx = with(density) { 240.dp.toPx() }

        val usableWidth = (containerWidthPx - 2 * horizontalInsetPx - handleSizePx).coerceAtLeast(1f)
        val usableHeight = (containerHeightPx - topInsetPx - bottomMarginPx - handleSizePx).coerceAtLeast(1f)
        val currentUsableWidth by rememberUpdatedState(usableWidth)
        val currentUsableHeight by rememberUpdatedState(usableHeight)
        val currentDockEnd by rememberUpdatedState(dockEnd)
        val currentVerticalFraction by rememberUpdatedState(verticalFraction)
        val isDockedEnd by remember { derivedStateOf { xFraction.value > 0.5f } }

        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { expanded = false })
                    },
            )
        }

        // Single gesture owner: tap toggles menu, drag relocates.
        // Delta is read before consume() so Compose does not zero out movement.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (horizontalInsetPx + xFraction.value * usableWidth).roundToInt(),
                        (topInsetPx + yFraction.value * usableHeight).roundToInt(),
                    )
                }
                .alpha(handleAlpha)
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                    shape = RoundedCornerShape(percent = 50),
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        isIdle = false
                        val session = FloaterDragSession(
                            startX = xFraction.value,
                            startY = yFraction.value,
                            touchSlopPx = viewConfiguration.touchSlop,
                        )
                        var grabHapticFired = false
                        var releasedNormally = false

                        val grabJob = scope.launch {
                            delay(250L)
                            if (!session.isDragging) {
                                grabHapticFired = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    if (event.changes.none { it.pressed }) break else continue
                                }
                                if (change.changedToUpIgnoreConsumed()) {
                                    change.consume()
                                    releasedNormally = true
                                    break
                                }
                                val delta = change.positionChangeIgnoreConsumed()
                                change.consume()
                                val startedDrag = session.onMove(
                                    delta = delta,
                                    currentXFraction = xFraction.value,
                                    currentYFraction = yFraction.value,
                                    usableWidthPx = currentUsableWidth,
                                    usableHeightPx = currentUsableHeight,
                                )
                                if (startedDrag) {
                                    dragging = true
                                    expanded = false
                                    grabJob.cancel()
                                    if (!grabHapticFired) {
                                        grabHapticFired = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                                if (session.isDragging) {
                                    val x = session.xFraction
                                    val y = session.yFraction
                                    scope.launch {
                                        xFraction.snapTo(x)
                                        yFraction.snapTo(y)
                                    }
                                }
                            }
                        } finally {
                            grabJob.cancel()
                            dragging = false
                        }

                        when (session.onEnd(releasedNormally)) {
                            FloaterReleaseAction.COMMIT_DRAG -> {
                                val endSide = session.xFraction > 0.5f
                                if (endSide != currentDockEnd) onDockChange(endSide)
                                if (session.yFraction !in currentVerticalFraction - 0.001f..currentVerticalFraction + 0.001f) {
                                    onPositionChange(session.yFraction)
                                }
                                scope.launch {
                                    xFraction.animateTo(if (endSide) 1f else 0f, DockSnapSpring)
                                }
                            }
                            FloaterReleaseAction.TOGGLE_MENU -> expanded = !expanded
                            FloaterReleaseAction.NONE -> Unit
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.CompareArrows,
                contentDescription = stringResource(ATMR.strings.reader_compare_handle),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        FloaterMenu(
            hasTranslation = hasTranslation,
            showingTranslated = showingTranslated,
            autoTranslate = autoTranslate,
            onSelectOriginal = {
                onSelectOriginal()
                expanded = false
            },
            onSelectTranslated = {
                onSelectTranslated()
                expanded = false
            },
            onToggleAutoTranslate = onToggleAutoTranslate,
            onColorizeChapter = {
                onColorizeChapter()
                expanded = false
            },
            onColorizePage = {
                onColorizePage()
                expanded = false
            },
            onOpenSettings = {
                onOpenSettings()
                expanded = false
            },
            onSizeChangedPx = { menuSizePx = it },
            visible = expanded,
            dockEnd = isDockedEnd,
            modifier = Modifier.offset {
                computeFloaterMenuOffset(
                    containerWidthPx = containerWidthPx,
                    containerHeightPx = containerHeightPx,
                    menuSizePx = menuSizePx,
                    fallbackWidthPx = defaultMenuWidthPx,
                    fallbackHeightPx = defaultMenuHeightPx,
                    horizontalInsetPx = horizontalInsetPx,
                    topInsetPx = topInsetPx,
                    bottomMarginPx = bottomMarginPx,
                    handleSizePx = handleSizePx,
                    menuSpacingPx = menuSpacingPx,
                    xFraction = xFraction.value,
                    yFraction = yFraction.value,
                    usableWidth = currentUsableWidth,
                    usableHeight = currentUsableHeight,
                )
            },
        )
    }
}

internal enum class FloaterReleaseAction {
    NONE,
    TOGGLE_MENU,
    COMMIT_DRAG,
}

internal class FloaterDragSession(
    startX: Float,
    startY: Float,
    private val touchSlopPx: Float,
) {
    var isDragging: Boolean = false
        private set
    var xFraction: Float = startX
        private set
    var yFraction: Float = startY
        private set
    private var accumulatedSlopDelta = Offset.Zero

    fun onMove(
        delta: Offset,
        currentXFraction: Float,
        currentYFraction: Float,
        usableWidthPx: Float,
        usableHeightPx: Float,
    ): Boolean {
        var justStartedDrag = false
        if (!isDragging) {
            accumulatedSlopDelta += delta
            if (accumulatedSlopDelta.getDistance() > touchSlopPx) {
                isDragging = true
                justStartedDrag = true
                xFraction = currentXFraction
                yFraction = currentYFraction
            }
        }
        if (isDragging) {
            xFraction = stepFloaterFraction(xFraction, delta.x, usableWidthPx)
            yFraction = stepFloaterFraction(yFraction, delta.y, usableHeightPx)
        }
        return justStartedDrag
    }

    fun onEnd(releasedNormally: Boolean): FloaterReleaseAction = when {
        isDragging -> FloaterReleaseAction.COMMIT_DRAG
        releasedNormally -> FloaterReleaseAction.TOGGLE_MENU
        else -> FloaterReleaseAction.NONE
    }
}

internal fun stepFloaterFraction(current: Float, deltaPx: Float, usableSpanPx: Float): Float {
    val span = usableSpanPx.coerceAtLeast(1f)
    return (current + deltaPx / span).coerceIn(0f, 1f)
}

internal fun computeFloaterMenuOffset(
    containerWidthPx: Int,
    containerHeightPx: Int,
    menuSizePx: IntSize,
    fallbackWidthPx: Float,
    fallbackHeightPx: Float,
    horizontalInsetPx: Float,
    topInsetPx: Float,
    bottomMarginPx: Float,
    handleSizePx: Float,
    menuSpacingPx: Float,
    xFraction: Float,
    yFraction: Float,
    usableWidth: Float,
    usableHeight: Float,
): IntOffset {
    val menuWidth = if (menuSizePx.width > 0) menuSizePx.width.toFloat() else fallbackWidthPx
    val menuHeight = if (menuSizePx.height > 0) menuSizePx.height.toFloat() else fallbackHeightPx
    val anchorLeft = horizontalInsetPx + xFraction * usableWidth
    val anchorTop = topInsetPx + yFraction * usableHeight
    val xMax = (containerWidthPx - horizontalInsetPx - menuWidth).coerceAtLeast(horizontalInsetPx)
    val menuLeft = if (xFraction > 0.5f) {
        (anchorLeft - menuSpacingPx - menuWidth).coerceIn(horizontalInsetPx, xMax)
    } else {
        (anchorLeft + handleSizePx + menuSpacingPx).coerceIn(horizontalInsetPx, xMax)
    }
    val yMax = (containerHeightPx - bottomMarginPx - menuHeight).coerceAtLeast(topInsetPx)
    val menuTop = (anchorTop + (handleSizePx - menuHeight) / 2f).coerceIn(topInsetPx, yMax)
    return IntOffset(menuLeft.roundToInt(), menuTop.roundToInt())
}

@Composable
private fun FloaterMenu(
    hasTranslation: Boolean,
    showingTranslated: Boolean,
    autoTranslate: Boolean,
    onSelectOriginal: () -> Unit,
    onSelectTranslated: () -> Unit,
    onToggleAutoTranslate: () -> Unit,
    onOpenSettings: () -> Unit,
    onColorizeChapter: () -> Unit,
    onColorizePage: () -> Unit,
    onSizeChangedPx: (IntSize) -> Unit,
    visible: Boolean,
    dockEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val origin = TransformOrigin(if (dockEnd) 1f else 0f, 0.5f)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + scaleIn(tween(150), initialScale = 0.88f, transformOrigin = origin),
        exit = fadeOut(tween(90)) + scaleOut(tween(120), targetScale = 0.88f, transformOrigin = origin),
        modifier = modifier,
    ) {
        val activeColor = MaterialTheme.colorScheme.primary
        val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
        val disabledAlpha = if (hasTranslation) 1f else 0.38f

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
            modifier = Modifier
                .widthIn(max = 200.dp)
                .onSizeChanged { onSizeChangedPx(IntSize(it.width, it.height)) },
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FloaterMenuRow(
                    icon = Icons.Outlined.Image,
                    label = stringResource(ATMR.strings.reader_compare_show_original),
                    tint = if (hasTranslation && !showingTranslated) activeColor else inactiveColor,
                    enabled = hasTranslation,
                    rowAlpha = disabledAlpha,
                    onClick = onSelectOriginal,
                )
                FloaterMenuRow(
                    icon = Icons.Outlined.Translate,
                    label = stringResource(ATMR.strings.reader_compare_show_translated),
                    tint = if (hasTranslation && showingTranslated) activeColor else inactiveColor,
                    enabled = hasTranslation,
                    rowAlpha = disabledAlpha,
                    onClick = onSelectTranslated,
                )
                Row(
                    modifier = Modifier
                        .clickable(onClick = onToggleAutoTranslate)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = if (autoTranslate) activeColor else inactiveColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(ATMR.strings.reader_floater_auto_translate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = autoTranslate,
                        onCheckedChange = { onToggleAutoTranslate() },
                    )
                }
                FloaterMenuRow(
                    icon = Icons.Outlined.Palette,
                    label = stringResource(ATMR.strings.reader_colorize_page),
                    tint = inactiveColor,
                    enabled = true,
                    rowAlpha = 1f,
                    onClick = onColorizePage,
                )
                FloaterMenuRow(
                    icon = Icons.Outlined.Palette,
                    label = stringResource(ATMR.strings.reader_colorize_chapter),
                    tint = inactiveColor,
                    enabled = true,
                    rowAlpha = 1f,
                    onClick = onColorizeChapter,
                )
                FloaterMenuRow(
                    icon = Icons.Outlined.Settings,
                    label = stringResource(ATMR.strings.reader_compare_open_settings),
                    tint = inactiveColor,
                    enabled = true,
                    rowAlpha = 1f,
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun FloaterMenuRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    rowAlpha: Float,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .alpha(if (enabled) rowAlpha else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}
