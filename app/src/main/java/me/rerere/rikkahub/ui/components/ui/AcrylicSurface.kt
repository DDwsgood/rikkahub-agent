package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * Background-only [HazeState] attached to [me.rerere.rikkahub.ui.pages.chat.AssistantBackground].
 * Used by inline chat cards (ChainOfThought, tables) so they can blur the underlying chat
 * background without capturing the scrolling LazyColumn itself.
 */
val LocalBackgroundHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * High-opacity frosted glass / acrylic surface matching RikkaUI cards while preserving strong
 * foreground text contrast.
 */
fun Modifier.acrylicSurface(
    shape: Shape,
    highOpacity: Boolean = true,
    borderWidth: Dp = 1.dp,
): Modifier = composed {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val settings = LocalSettings.current
    val bgHazeState = LocalBackgroundHazeState.current

    val darkAlpha = if (highOpacity) 0.55f else 0.45f
    val lightAlpha = if (highOpacity) 0.75f else 0.62f
    val surfaceColor = if (dark) {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = darkAlpha)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = lightAlpha)
    }
    val borderBrush = if (dark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.07f),
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.75f),
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
            )
        )
    }

    val surfaceModifier = if (settings.displaySetting.enableBlurEffect && bgHazeState != null) {
        Modifier
            .clip(shape)
            .hazeEffect(state = bgHazeState) {
                blurEffect {
                    blurRadius = 20.dp
                    noiseFactor = 0.04f
                    backgroundColor = surfaceColor
                    colorEffects = listOf(HazeColorEffect.TintColor(surfaceColor))
                    fallbackTint = HazeColorEffect.TintColor(surfaceColor)
                }
            }
    } else {
        Modifier
            .clip(shape)
            .background(surfaceColor)
    }

    this
        .then(surfaceModifier)
        .border(borderWidth, borderBrush, shape)
}

@Composable
fun acrylicHeaderTint(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Color.White.copy(alpha = 0.07f)
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
}

@Composable
fun acrylicSubSurfaceTint(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Color.White.copy(alpha = 0.055f)
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
}

@Composable
fun acrylicDividerColor(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Color.White.copy(alpha = 0.11f)
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)
}
