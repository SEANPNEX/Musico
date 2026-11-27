package com.musico.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.musico.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.abs

@Immutable
data class SwipeActionConfig(
    val label: String,
    val icon: ImageVector,
    val isDestructive: Boolean = false,
    val thresholdFraction: Float = 0.32f,
    val minThresholdDp: Dp = 88.dp,
    val maxThresholdDp: Dp = 180.dp,
    val onTrigger: () -> Unit,
)

@Composable
fun MusicoSwipeActionRow(
    modifier: Modifier = Modifier,
    startAction: SwipeActionConfig? = null,
    endAction: SwipeActionConfig? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val leftAction = if (isRtl) endAction else startAction
    val rightAction = if (isRtl) startAction else endAction
    val canSwipe = enabled && (leftAction != null || rightAction != null)

    val currentCanSwipe by rememberUpdatedState(canSwipe)
    val currentLeftAction by rememberUpdatedState(leftAction)
    val currentRightAction by rememberUpdatedState(rightAction)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()

    val offsetX = remember { Animatable(0f) }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var wasArmed by remember { mutableStateOf(false) }

    fun thresholdPxFor(cfg: SwipeActionConfig): Float {
        val minPx = with(density) { cfg.minThresholdDp.toPx() }
        val maxPx = with(density) { cfg.maxThresholdDp.toPx() }.coerceAtLeast(minPx)
        return if (rowWidthPx > 0) {
            (rowWidthPx * cfg.thresholdFraction).coerceIn(minPx, maxPx)
        } else {
            minPx
        }
    }

    LaunchedEffect(canSwipe) {
        if (!canSwipe) {
            wasArmed = false
            if (offsetX.value != 0f) {
                offsetX.snapTo(0f)
            }
        }
    }

    val draggableState = rememberDraggableState { delta ->
        val cur = offsetX.value
        val tentative = cur + delta
        val targetAction = when {
            tentative > 0f -> currentLeftAction
            tentative < 0f -> currentRightAction
            cur > 0f -> currentLeftAction
            cur < 0f -> currentRightAction
            else -> null
        }
        val next = if (targetAction == null) {
            0f
        } else {
            val threshold = thresholdPxFor(targetAction)
            val dampedDelta = when {
                abs(cur) >= threshold -> delta * 0.35f
                abs(tentative) > threshold -> {
                    val sign = if (tentative > 0f) 1f else -1f
                    val toThreshold = sign * threshold - cur
                    val beyond = (tentative - sign * threshold) * 0.35f
                    toThreshold + beyond
                }
                else -> delta
            }
            val candidate = cur + dampedDelta
            when {
                currentLeftAction == null && candidate > 0f -> 0f
                currentRightAction == null && candidate < 0f -> 0f
                else -> candidate
            }
        }
        val activeCfg = when {
            next > 0f -> currentLeftAction
            next < 0f -> currentRightAction
            else -> null
        }
        val isArmedNow = activeCfg != null && abs(next) >= thresholdPxFor(activeCfg)
        if (!wasArmed && isArmedNow) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (wasArmed && !isArmedNow) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        wasArmed = isArmedNow
        scope.launch {
            offsetX.snapTo(next)
        }
    }

    val offsetPx = offsetX.value
    val gapPx = abs(offsetPx)
    val isLeftExposed = offsetPx > 0f
    val exposedConfig = when {
        offsetPx > 0.5f -> leftAction
        offsetPx < -0.5f -> rightAction
        else -> null
    }
    val isArmed = exposedConfig != null && gapPx >= thresholdPxFor(exposedConfig)

    val colorSpec = if (reduceMotion) snap<Color>() else tween<Color>(durationMillis = 160)
    val floatSpec = if (reduceMotion) snap<Float>() else tween<Float>(durationMillis = 160)
    val targetBgColor = when {
        exposedConfig == null || !isArmed -> MaterialTheme.colorScheme.surfaceContainerHigh
        exposedConfig.isDestructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val targetContentColor = when {
        exposedConfig == null || !isArmed -> MaterialTheme.colorScheme.onSurfaceVariant
        exposedConfig.isDestructive -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onPrimary
    }
    val backBgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = colorSpec,
        label = "swipe_back_bg",
    )
    val backContentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = colorSpec,
        label = "swipe_back_content",
    )
    val armedBaseAlpha by animateFloatAsState(
        targetValue = if (isArmed) 1f else 0.92f,
        animationSpec = floatSpec,
        label = "swipe_back_alpha",
    )

    val iconScale = remember { Animatable(1f) }
    LaunchedEffect(isArmed, reduceMotion) {
        if (reduceMotion) {
            iconScale.snapTo(1f)
        } else if (isArmed) {
            iconScale.snapTo(0.9f)
            iconScale.animateTo(
                targetValue = 1.15f,
                animationSpec = tween(durationMillis = 75, easing = FastOutSlowInEasing),
            )
            iconScale.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        } else {
            iconScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 120),
            )
        }
    }

    val gapDp = with(density) { gapPx.toDp() }
    val labelAlpha by animateFloatAsState(
        targetValue = if (gapDp >= 72.dp) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 140),
        label = "swipe_label_alpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidthPx = it.width }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                enabled = canSwipe,
                onDragStarted = {
                    val cur = offsetX.value
                    val activeCfg = when {
                        cur > 0f -> currentLeftAction
                        cur < 0f -> currentRightAction
                        else -> null
                    }
                    wasArmed = activeCfg != null && abs(cur) >= thresholdPxFor(activeCfg)
                },
                onDragStopped = { _ ->
                    val cur = offsetX.value
                    val activeCfg = when {
                        cur > 0f -> currentLeftAction
                        cur < 0f -> currentRightAction
                        else -> null
                    }
                    val triggered = if (currentCanSwipe && activeCfg != null && abs(cur) >= thresholdPxFor(activeCfg)) {
                        activeCfg
                    } else {
                        null
                    }
                    wasArmed = false
                    triggered?.onTrigger?.invoke()
                    scope.launch {
                        if (reduceMotion) {
                            offsetX.snapTo(0f)
                        } else {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                        }
                    }
                },
            ),
    ) {
        val liftDistancePx = with(density) { 24.dp.toPx() }
        val dragFraction = if (liftDistancePx > 0f) (gapPx / liftDistancePx).coerceIn(0f, 1f) else 0f
        val cardCorner = (12f * dragFraction).dp
        val cardCornerPx = with(density) { cardCorner.toPx() }
        val cardShape = RoundedCornerShape(cardCorner)
        val cardBg = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f * dragFraction)
        val cardBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f * dragFraction)
        val effectiveBackBgColor = backBgColor.copy(alpha = armedBaseAlpha * dragFraction)
        val cardClipPath = remember { Path() }

        if (exposedConfig != null && gapPx > 0.5f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clipToBounds()
                    .drawWithContent {
                        cardClipPath.reset()
                        cardClipPath.addRoundRect(
                            RoundRect(
                                left = offsetPx,
                                top = 0f,
                                right = size.width + offsetPx,
                                bottom = size.height,
                                cornerRadius = CornerRadius(cardCornerPx, cardCornerPx),
                            )
                        )
                        clipPath(path = cardClipPath, clipOp = ClipOp.Difference) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    .background(effectiveBackBgColor)
                    .padding(horizontal = 18.dp),
                contentAlignment = if (isLeftExposed) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isLeftExposed) {
                        Icon(
                            imageVector = exposedConfig.icon,
                            contentDescription = exposedConfig.label,
                            tint = backContentColor,
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer {
                                    scaleX = iconScale.value
                                    scaleY = iconScale.value
                                },
                        )
                        if (labelAlpha > 0.01f) {
                            Text(
                                text = exposedConfig.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = backContentColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.graphicsLayer { alpha = labelAlpha },
                            )
                        }
                    } else {
                        if (labelAlpha > 0.01f) {
                            Text(
                                text = exposedConfig.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = backContentColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.graphicsLayer { alpha = labelAlpha },
                            )
                        }
                        Icon(
                            imageVector = exposedConfig.icon,
                            contentDescription = exposedConfig.label,
                            tint = backContentColor,
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer {
                                    scaleX = iconScale.value
                                    scaleY = iconScale.value
                                },
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetPx }
                .then(
                    if (dragFraction > 0.001f) {
                        Modifier
                            .clip(cardShape)
                            .background(cardBg)
                            .border(width = 1.dp, color = cardBorder, shape = cardShape)
                    } else {
                        Modifier
                    }
                ),
        ) {
            content()
        }
    }
}
