package app.animix.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import app.animix.core.ThemeMode

@Immutable
object AniMixShapes {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(14.dp)
    val large = RoundedCornerShape(20.dp)
    val extraLarge = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(percent = 50)
}

private val LocalColors = staticCompositionLocalOf { DarkColors }
private val LocalType = staticCompositionLocalOf<AniMixType> { error("AniMixTheme is missing") }

object AniMixTheme {
    val colors: AniMixColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: AniMixType
        @Composable @ReadOnlyComposable get() = LocalType.current
    val shapes = AniMixShapes
}

@Composable
fun AniMixTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val colors = if (dark) DarkColors else LightColors
    val type = rememberAniMixType(colors)
    CompositionLocalProvider(LocalColors provides colors, LocalType provides type) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = type.toMaterial(), content = content)
    }
}

/** Material components (text fields, ripples, sliders) follow the AniMix palette. */
private fun AniMixColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = textPrimary,
        secondary = accentStrong,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceRaised,
        onSurfaceVariant = textSecondary,
        surfaceContainer = surfaceRaised,
        surfaceContainerHigh = surfaceRaised,
        outline = outline,
        outlineVariant = outline,
        error = danger,
    )
}
