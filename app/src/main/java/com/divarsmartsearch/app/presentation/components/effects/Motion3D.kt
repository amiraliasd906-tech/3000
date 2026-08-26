package com.divarsmartsearch.app.presentation.components.effects

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lightweight, dependency-free "3D" motion + depth helpers for Compose.
 *
 * These deliberately avoid gesture-consuming pointerInput blocks (drag/tap
 * detectors), since a card that hijacks touch to compute a tilt angle would
 * fight the same LazyColumn scroll and onClick handling these components
 * already rely on. Everything here is driven either by state the caller
 * already owns (e.g. an existing `isPressed` from an interactionSource) or
 * by a self-contained ambient animation — so it layers on top of existing
 * screens with zero risk to scrolling/tap behavior.
 */

/**
 * Applies a subtle, perspective-correct "pressed into the screen" tilt +
 * depth effect driven purely by [pressed] (no extra touch handling — reuse
 * whatever `isPressed` the component already derives from its own
 * interactionSource/onClick).
 */
@Composable
fun Modifier.pressDepth3D(
    pressed: Boolean,
    maxTiltDegrees: Float = 6f,
): Modifier {
    val depth by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressDepth3D",
    )
    return this.graphicsLayer {
        rotationX = maxTiltDegrees * depth
        scaleX = 1f - 0.025f * depth
        scaleY = 1f - 0.025f * depth
        translationY = 3f * depth * density
        cameraDistance = 14f * density
    }
}

/**
 * A soft, layered "glow" ring around a rounded-rect shape — built from a
 * few widening, fading strokes rather than a real blur, so it renders
 * identically on every API level (minSdk 26) instead of only on the
 * devices that support [androidx.compose.ui.graphics.RenderEffect].
 */
fun Modifier.glowBorder(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp = 1.5.dp,
): Modifier = drawBehind {
    val stroke = strokeWidth.toPx()
    val corner = cornerRadius.toPx()
    for (layer in 3 downTo 1) {
        drawRoundRect(
            color = color.copy(alpha = 0.09f * layer),
            style = Stroke(width = stroke + layer * 5f),
            cornerRadius = CornerRadius(corner + layer * 2f),
        )
    }
    drawRoundRect(
        color = color.copy(alpha = 0.85f),
        style = Stroke(width = stroke),
        cornerRadius = CornerRadius(corner),
    )
}

/**
 * A moving light-sweep overlay for skeleton/placeholder shapes — the
 * actual "shimmer" a loading skeleton needs (as opposed to a static
 * flat-colored box).
 */
@Composable
fun Modifier.shimmerSweep(): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmerSweep")
    val translate by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    return this.drawBehind {
        val brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0f),
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0f),
            ),
            start = Offset(translate, 0f),
            end = Offset(translate + 260f, size.height),
        )
        drawRect(brush = brush)
    }
}

/**
 * A slow, continuous 3D bob + tilt for a static icon/graphic (e.g. the
 * empty-state icon) — purely ambient, no touch input needed.
 */
@Composable
fun Modifier.float3D(): Modifier {
    val transition = rememberInfiniteTransition(label = "float3D")
    val bob by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floatBob",
    )
    val rotate by transition.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floatRotate",
    )
    return this.graphicsLayer {
        translationY = bob * 7f * density
        rotationY = rotate
        cameraDistance = 10f * density
    }
}

/**
 * Full-bleed, animated "aurora" backdrop: two soft radial-gradient blobs
 * that slowly drift, giving screens a sense of depth/light behind the flat
 * content instead of a single solid background color. Cheap to draw (two
 * gradient circles), safe on every device (no RenderEffect/blur needed).
 */
@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    colorA: Color,
    colorB: Color,
) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDrift",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val blob1Center = Offset(w * (0.22f + 0.12f * drift), h * 0.12f)
        val blob1Radius = w * 0.65f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colorA.copy(alpha = 0.22f), Color.Transparent),
                center = blob1Center,
                radius = blob1Radius,
            ),
            radius = blob1Radius,
            center = blob1Center,
        )

        val blob2Center = Offset(w * (0.82f - 0.10f * drift), h * 0.30f)
        val blob2Radius = w * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colorB.copy(alpha = 0.16f), Color.Transparent),
                center = blob2Center,
                radius = blob2Radius,
            ),
            radius = blob2Radius,
            center = blob2Center,
        )
    }
}

/**
 * One-line wrapper used at the top of every screen: draws the animated
 * [AuroraBackdrop] using the app's own theme colors, then places the
 * screen's normal content (its Scaffold, etc.) on top of it. Pulled out
 * once here so every screen gets the exact same depth/backdrop treatment
 * instead of hand-rolling the Box + AuroraBackdrop + color wiring each
 * time — one consistent look across the whole app, not just two screens.
 */
@Composable
fun AuroraScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AuroraBackdrop(
            modifier = Modifier.fillMaxSize(),
            colorA = MaterialTheme.colorScheme.primary,
            colorB = MaterialTheme.colorScheme.secondary,
        )
        content()
    }
}

/**
 * Transparent top-bar colors, so a screen's TopAppBar lets the
 * [AuroraScreenBackground] show through behind it (a "glass" bar) instead
 * of painting over it with a flat surface color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun transparentTopBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)

/**
 * A translucent "frosted glass" look for a rounded container: a soft
 * gradient fill plus a thin bright top edge (like light catching the top
 * of a pane of glass), so non-listing surfaces (settings rows, filter
 * chips, seller-report cards) get the same depth language as the main
 * listing cards instead of a flat solid fill.
 */
fun Modifier.glassSurface(
    cornerRadius: Dp = 18.dp,
    tint: Color = Color.White,
): Modifier = this
    .drawBehind {
        val corner = cornerRadius.toPx()
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(tint.copy(alpha = 0.08f), tint.copy(alpha = 0.02f)),
            ),
            cornerRadius = CornerRadius(corner),
        )
        drawRoundRect(
            color = tint.copy(alpha = 0.14f),
            style = Stroke(width = 1f),
            cornerRadius = CornerRadius(corner),
        )
    }

/**
 * Small press-reactive "lift" for tappable icons/buttons/rows that aren't
 * full listing cards — same idea as [pressDepth3D] but tuned lighter, so
 * every tappable surface in the app (not just cards) has some sense of
 * physical depth when touched.
 */
@Composable
fun Modifier.tapLift3D(pressed: Boolean): Modifier {
    val depth by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "tapLift3D",
    )
    return this.graphicsLayer {
        val s = 1f - 0.06f * depth
        scaleX = s
        scaleY = s
        rotationX = 3f * depth
        cameraDistance = 10f * density
    }
}
