package app.animix.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalRippleConfiguration
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
    val medium = RoundedCornerShape(16.dp)
    val large = RoundedCornerShape(22.dp)
    val extraLarge = RoundedCornerShape(30.dp)
    val sheet = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    val pill = RoundedCornerShape(percent = 50)
}

private val LocalColors = staticCompositionLocalOf { HorizonDusk }
private val LocalType = staticCompositionLocalOf<AniMixType> { error("AniMixTheme is missing") }

object AniMixTheme {
    val colors: AniMixColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: AniMixType
        @Composable @ReadOnlyComposable get() = LocalType.current
    val shapes = AniMixShapes
}

/** A reward theme that is not open at this level shows as the system theme; the choice itself is kept. */
fun ThemeMode.at(level: Int): ThemeMode = if (level >= unlockLevel) this else ThemeMode.System

fun themeColors(mode: ThemeMode, systemDark: Boolean): AniMixColors = when (mode) {
    ThemeMode.System -> if (systemDark) HorizonDusk else HorizonDawn
    ThemeMode.Dusk -> HorizonDusk
    ThemeMode.Dawn -> HorizonDawn
    ThemeMode.Ice -> HorizonIce
    ThemeMode.Coal -> HorizonCoal
}

@Composable
fun AniMixTheme(mode: ThemeMode, animationsEnabled: Boolean = true, batterySaver: Boolean = false, liquidGlass: Boolean = true, content: @Composable () -> Unit) {
    val colors = themeColors(mode, isSystemInDarkTheme())
    val type = rememberAniMixType(colors)
    val ripple = LocalRippleConfiguration.current.takeIf { animationsEnabled && !batterySaver }
    CompositionLocalProvider(LocalColors provides colors, LocalType provides type,
        LocalRippleConfiguration provides ripple,
        LocalUiPerformance provides UiPerformance(animationsEnabled && !batterySaver, batterySaver, liquidGlass && !batterySaver)) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = type.toMaterial()) {
            // Every press in AniMix shows the same veil, instead of Material's ripple (off without animations).
            CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides
                app.animix.ui.components.PressIndication(colors.textPrimary, animationsEnabled && !batterySaver), content = content)
        }
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
