package app.animix.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The New Horizons palettes, from the brand sheet:
 * graphite #08080F, light graphite #1A1A1F, pastel pink #FFB4C8, light #FBF8FA,
 * accent gradient #FF9EC6 -> #FF889D.
 * Horizon Dusk: graphite + pastel pink. Horizon Dawn: porcelain (#FBF8FA) + muted rose.
 */
@Immutable
data class AniMixColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceGlass: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val scrim: Color,
    val star: Color,
    val danger: Color,
    /** Accent used for text on tinted (accentSoft) backgrounds. */
    val accentText: Color,
    /** Top edge of a neutral rim. */
    val rimHighlight: Color,
    /** How strong accent glows are; light backgrounds need less. */
    val glowScale: Float,
    /** Quiet translucent fill for chips and small controls. */
    val chip: Color,
) {
    val accentGradient: Brush get() = Brush.horizontalGradient(listOf(gradientStart, gradientEnd))
}

/** Horizon Dusk — the dark theme: graphite and pastel pink. */
val HorizonDusk = AniMixColors(
    isDark = true,
    background = Color(0xFF08080F),
    surface = Color(0xFF1A1A1F),
    surfaceRaised = Color(0xFF222229),
    surfaceGlass = Color(0xB31A1A1F),
    outline = Color(0xFF29292E),
    textPrimary = Color(0xFFFBF8FA),
    textSecondary = Color(0xFFA7A1A6),
    textMuted = Color(0xFF6E696E),
    accent = Color(0xFFFFB4C8),
    accentStrong = Color(0xFFFF9EBB),
    accentSoft = Color(0x26FFB4C8),
    onAccent = Color(0xFF2A1219),
    gradientStart = Color(0xFFFF9EC6),
    gradientEnd = Color(0xFFFF889D),
    scrim = Color(0xCC08080F),
    star = Color(0xFFFFC978),
    danger = Color(0xFFFF7A85),
    accentText = Color(0xFFFFD3DF),
    rimHighlight = Color(0x14FFFFFF),
    glowScale = 1f,
    chip = Color(0x14FFFFFF),
)

/** Horizon Dawn — the light theme: porcelain and muted rose. */
val HorizonDawn = AniMixColors(
    isDark = false,
    background = Color(0xFFFBF8FA),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF2ECEF),
    surfaceGlass = Color(0xCCFFFFFF),
    outline = Color(0xFFE8E0E4),
    textPrimary = Color(0xFF2B2627),
    textSecondary = Color(0xFF6E6567),
    textMuted = Color(0xFFA2989A),
    accent = Color(0xFFB57F8C),
    accentStrong = Color(0xFF94606D),
    accentSoft = Color(0x24B57F8C),
    onAccent = Color(0xFFFFFFFF),
    gradientStart = Color(0xFFCB98A4),
    gradientEnd = Color(0xFFAD7482),
    scrim = Color(0x992B2627),
    star = Color(0xFFE59A2E),
    danger = Color(0xFFD94B5A),
    accentText = Color(0xFF8A5462),
    rimHighlight = Color(0xB3FFFFFF),
    glowScale = 0.7f,
    chip = Color(0x0D2B2627),
)
