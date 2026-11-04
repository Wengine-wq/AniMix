package app.animix.ui.screens.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.AppIcon
import app.animix.core.ThemeMode
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixColors
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.HorizonDawn
import app.animix.ui.theme.HorizonDusk
import app.animix.ui.theme.themeColors

/**
 * «Внешний вид»: five themes as small previews of their own palettes (the turquoise pair opens on
 * levels 5 and 10 and is shown with a lock until then), the app icon in the four colourings, and animations.
 */
@Composable
internal fun AppearanceSection(graph: AppGraph) {
    val settings by graph.settings.state.collectAsState()
    val level by graph.level.collectAsState()
    Txt("Тема", AniMixTheme.type.heading)
    Spacer(Modifier.height(12.dp))
    OptionGrid(ThemeMode.entries) { mode, modifier ->
        val open = level.level >= mode.unlockLevel
        ThemeCard(mode, settings.themeMode == mode, open, modifier) {
            graph.settings.update { it.copy(themeMode = mode) }
            graph.achievements.themeChosen(mode.name)
        }
    }
    Txt(themeNote(settings.themeMode, level.level), AniMixTheme.type.micro, modifier = Modifier.padding(start = 4.dp, top = 10.dp))

    Spacer(Modifier.height(28.dp))
    Txt("Значок приложения", AniMixTheme.type.heading)
    Spacer(Modifier.height(12.dp))
    OptionGrid(AppIcon.entries, columns = 4) { icon, modifier ->
        IconCard(icon, settings.appIcon == icon, level.level >= icon.unlockLevel, modifier) {
            graph.settings.update { it.copy(appIcon = icon) }
        }
    }
    Txt(if (app.animix.core.Platform.name == "Android") "Значок на рабочем столе сменится, когда вы выйдете из AniMix; лаунчеру может понадобиться несколько секунд."
        else "Значок окна и панели задач меняется сразу.", AniMixTheme.type.micro, modifier = Modifier.padding(start = 4.dp, top = 10.dp))

    Spacer(Modifier.height(28.dp))
    Panel {
        ToggleRow("Анимации и переходы", settings.animationsAllowed,
            { value -> graph.settings.update { it.copy(animationsEnabled = value) } }, AniMixIcons.Play,
            subtitle = if (settings.batterySaver) "Отключены режимом экономии батареи" else "Плавные переходы, нажатия и индикаторы",
            enabled = !settings.batterySaver)
    }
}

private fun themeNote(mode: ThemeMode, level: Int): String = when {
    level < mode.unlockLevel -> "${mode.label} откроется на ${mode.unlockLevel}-м уровне. Пока действует тема системы."
    mode == ThemeMode.Ice || mode == ThemeMode.Coal -> "Бирюза, лёд и уголь — вторая палитра AniMix, награда за уровень."
    else -> "Ледяная тема открывается на 5-м уровне, угольная — на 10-м."
}

/** Three cards in a row on a phone, all five in one row on a wide screen. */
@Composable
private fun <T> OptionGrid(items: List<T>, columns: Int? = null, card: @Composable (T, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = columns ?: if (maxWidth >= 560.dp) items.size else 3
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { card(it, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun OptionSurface(selected: Boolean, open: Boolean, modifier: Modifier, description: String, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = AniMixTheme.shapes.medium
    Column(
        modifier.pressScale(interaction).clip(shape)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.accent else colors.outline, shape)
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = open, role = Role.RadioButton, onClickLabel = description, onClick = onClick)
            .focusOutline(interaction, shape).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun ThemeCard(mode: ThemeMode, selected: Boolean, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    OptionSurface(selected, open, modifier, mode.label, onClick) {
        Box(Modifier.fillMaxWidth().height(56.dp).clip(AniMixTheme.shapes.small).alpha(if (open) 1f else 0.45f)) {
            if (mode == ThemeMode.System) Row(Modifier.fillMaxSize()) {
                PaletteSwatch(HorizonDusk, Modifier.weight(1f).fillMaxHeight())
                PaletteSwatch(HorizonDawn, Modifier.weight(1f).fillMaxHeight())
            } else PaletteSwatch(themeColors(mode, true), Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(8.dp))
        Txt(mode.label.removePrefix("Horizon "), AniMixTheme.type.label, color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1, align = TextAlign.Center)
        if (!open) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Icon(AniMixIcons.Lock, null, Modifier.size(11.dp), tint = colors.textMuted)
            Spacer(Modifier.width(3.dp))
            Txt("${mode.unlockLevel} ур.", AniMixTheme.type.micro, maxLines = 1)
        }
    }
}

/** A tiny screen in the palette: its background, a card and the accent. */
@Composable
private fun PaletteSwatch(palette: AniMixColors, modifier: Modifier) {
    Box(modifier.background(palette.background).padding(8.dp)) {
        Column(Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.width(28.dp).height(6.dp).clip(AniMixTheme.shapes.pill).background(palette.textPrimary.copy(alpha = 0.8f)))
            Box(Modifier.width(18.dp).height(4.dp).clip(AniMixTheme.shapes.pill).background(palette.textSecondary.copy(alpha = 0.6f)))
        }
        Box(Modifier.align(Alignment.BottomStart).width(34.dp).height(12.dp).clip(AniMixTheme.shapes.pill).background(palette.accentGradient))
        Box(Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(palette.surface).border(1.dp, palette.outline, CircleShape))
    }
}

@Composable
private fun IconCard(icon: AppIcon, selected: Boolean, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    OptionSurface(selected, open, modifier, "Значок «${icon.label}»", onClick) {
        Box(contentAlignment = Alignment.Center) {
            Image(appIconPainter(icon), null, Modifier.size(52.dp).alpha(if (open) 1f else 0.4f))
            if (!open) Icon(AniMixIcons.Lock, null, Modifier.size(16.dp), tint = colors.textPrimary)
        }
        Spacer(Modifier.height(6.dp))
        Txt(if (open) icon.label else "${icon.unlockLevel} ур.", AniMixTheme.type.micro,
            color = if (selected) colors.textPrimary else colors.textSecondary, maxLines = 1, align = TextAlign.Center)
    }
}
