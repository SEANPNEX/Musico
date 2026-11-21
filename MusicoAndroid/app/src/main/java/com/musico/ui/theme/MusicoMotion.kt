package com.musico.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Material 3 expressive motion tokens and transition specs for Musico.
 */
object MusicoMotion {
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val StandardEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    const val ENTER_DURATION_MS = 220
    const val EXIT_DURATION_MS = 90
    const val ENTER_DELAY_MS = 90

    val SpatialSpring = spring<IntOffset>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow,
    )

    val SizeSpring = spring<IntSize>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow,
    )

    val ArtworkScaleSpring = spring<Float>(
        dampingRatio = 0.74f,
        stiffness = Spring.StiffnessMediumLow,
    )

    val ExpressiveSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    val EffectsSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    fun fadeThroughTransform(): ContentTransform {
        val enter = fadeIn(
            animationSpec = tween(
                durationMillis = ENTER_DURATION_MS,
                delayMillis = ENTER_DELAY_MS,
                easing = EmphasizedDecelerateEasing,
            )
        ) + scaleIn(
            initialScale = 0.94f,
            animationSpec = tween(
                durationMillis = ENTER_DURATION_MS,
                delayMillis = ENTER_DELAY_MS,
                easing = EmphasizedDecelerateEasing,
            )
        )
        val exit = fadeOut(
            animationSpec = tween(
                durationMillis = EXIT_DURATION_MS,
                easing = EmphasizedAccelerateEasing,
            )
        )
        return enter togetherWith exit
    }

    fun sharedAxisXTransform(forward: Boolean): ContentTransform {
        val sign = if (forward) 1 else -1
        val enter = slideInHorizontally(
            animationSpec = tween(
                durationMillis = 280,
                easing = EmphasizedEasing,
            ),
            initialOffsetX = { fullWidth -> sign * (fullWidth / 4) }
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = ENTER_DURATION_MS,
                delayMillis = 60,
                easing = EmphasizedDecelerateEasing,
            )
        )
        val exit = slideOutHorizontally(
            animationSpec = tween(
                durationMillis = 240,
                easing = EmphasizedEasing,
            ),
            targetOffsetX = { fullWidth -> -sign * (fullWidth / 5) }
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = EXIT_DURATION_MS,
                easing = EmphasizedAccelerateEasing,
            )
        )
        return enter togetherWith exit
    }

    val miniPlayerVisibilityTransition: Pair<EnterTransition, ExitTransition> =
        (slideInVertically(
            animationSpec = SpatialSpring,
            initialOffsetY = { it }
        ) + expandVertically(
            animationSpec = SizeSpring,
            expandFrom = Alignment.Bottom,
        ) + fadeIn(
            animationSpec = tween(durationMillis = 180, easing = EmphasizedDecelerateEasing)
        )) to (slideOutVertically(
            animationSpec = SpatialSpring,
            targetOffsetY = { it }
        ) + shrinkVertically(
            animationSpec = SizeSpring,
            shrinkTowards = Alignment.Bottom,
        ) + fadeOut(
            animationSpec = tween(durationMillis = 120, easing = EmphasizedAccelerateEasing)
        ))

    val miniPlayerEnter: EnterTransition
        get() = miniPlayerVisibilityTransition.first

    val miniPlayerExit: ExitTransition
        get() = miniPlayerVisibilityTransition.second

    val fullPlayerEnter: EnterTransition =
        slideInVertically(
            animationSpec = SpatialSpring,
            initialOffsetY = { it }
        ) + fadeIn(
            animationSpec = tween(durationMillis = 220, easing = EmphasizedDecelerateEasing)
        )

    val fullPlayerExit: ExitTransition =
        slideOutVertically(
            animationSpec = SpatialSpring,
            targetOffsetY = { it }
        ) + fadeOut(
            animationSpec = tween(durationMillis = 160, easing = EmphasizedAccelerateEasing)
        )

    val sidePanelEnter: EnterTransition =
        fadeIn(
            animationSpec = tween(durationMillis = 200, easing = EmphasizedDecelerateEasing)
        ) + expandHorizontally(
            animationSpec = SizeSpring,
            expandFrom = Alignment.End,
        )

    val sidePanelExit: ExitTransition =
        fadeOut(
            animationSpec = tween(durationMillis = 140, easing = EmphasizedAccelerateEasing)
        ) + shrinkHorizontally(
            animationSpec = SizeSpring,
            shrinkTowards = Alignment.End,
        )
}
