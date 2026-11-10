package app.animix.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.animix.resources.Res
import app.animix.resources.inter
import org.jetbrains.compose.resources.Font

@Immutable
data class AniMixType(
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val subheading: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val micro: TextStyle,
) {
    fun toMaterial(): Typography = Typography(
        displaySmall = display,
        headlineSmall = title,
        titleLarge = heading,
        titleMedium = subheading,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = caption,
        labelLarge = label,
        labelMedium = label,
        labelSmall = micro,
    )
}

@Composable
fun rememberAniMixType(colors: AniMixColors): AniMixType {
    val inter = FontFamily(
        Font(Res.font.inter, FontWeight.Normal),
        Font(Res.font.inter, FontWeight.Medium),
        Font(Res.font.inter, FontWeight.SemiBold),
        Font(Res.font.inter, FontWeight.Bold),
    )
    val base = TextStyle(fontFamily = inter, color = colors.textPrimary)
    return AniMixType(
        display = base.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
        title = base.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
        heading = base.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em),
        subheading = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        body = base.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodyStrong = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        caption = base.copy(fontSize = 12.sp, lineHeight = 16.sp, color = colors.textSecondary),
        label = base.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        micro = base.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = colors.textSecondary),
    )
}
