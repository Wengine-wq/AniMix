package app.animix.ui.components

import app.animix.ui.theme.animateMotionColorAsState as animateColorAsState
import app.animix.ui.theme.motionContentSize as animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixMotion
import app.animix.ui.theme.AniMixTheme

data class SelectionOption(val key: String, val title: String, val subtitle: String)

/** Shared inline selector for watching and downloads. The selected choice stays visible. */
@Composable
fun SelectionList(
    title: String,
    options: List<SelectionOption>,
    selectedKey: String?,
    icon: ImageVector,
    onSelect: (String) -> Unit,
) {
    if (options.isEmpty()) return
    val colors = AniMixTheme.colors
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val selected = options.firstOrNull { it.key == selectedKey } ?: options.first()
    // A long list of dubbings gets a search line: nobody scrolls through sixty studios to find one.
    val searchable = options.size > 8
    val shown = when {
        !open -> listOf(selected)
        filter.isBlank() -> options
        else -> options.filter { it.title.contains(filter.trim(), ignoreCase = true) || it.subtitle.contains(filter.trim(), ignoreCase = true) }
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, AniMixTheme.type.heading, modifier = Modifier.weight(1f))
            if (options.size > 1) {
                // The same quiet chip as «Все ›» of the sections.
                Row(Modifier.clip(AniMixTheme.shapes.pill).background(colors.chip)
                    .clickable(role = Role.Button) { open = !open; filter = "" }.padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Txt(if (open) "Свернуть" else "Сменить · ${options.size}", AniMixTheme.type.label, color = colors.textPrimary, maxLines = 1)
                    Spacer(Modifier.width(2.dp))
                    Icon(if (open) AniMixIcons.ChevronUp else AniMixIcons.ChevronDown, null, Modifier.size(14.dp), tint = colors.textSecondary)
                }
            }
        }
        if (open && searchable) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).height(44.dp).clip(AniMixTheme.shapes.pill).background(colors.chip)
                .rim(AniMixTheme.shapes.pill, accent = false).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AniMixIcons.Search, null, Modifier.size(16.dp), tint = colors.textMuted)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (filter.isEmpty()) Txt("Найти озвучку", AniMixTheme.type.body, color = colors.textMuted, maxLines = 1)
                    androidx.compose.foundation.text.BasicTextField(filter, { filter = it }, singleLine = true, textStyle = AniMixTheme.type.body,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent), modifier = Modifier.fillMaxWidth())
                }
                if (filter.isNotEmpty()) Icon(AniMixIcons.Close, "Очистить", Modifier.size(20.dp).clip(AniMixTheme.shapes.pill).clickable { filter = "" }.padding(2.dp),
                    tint = colors.textSecondary)
            }
        }
        if (open && shown.isEmpty()) Txt("Нет озвучки с таким названием", AniMixTheme.type.caption, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        if (shown.isNotEmpty()) Panel(Modifier.animateContentSize(AniMixMotion.selectionSpring()).selectableGroup()) {
            shown.forEachIndexed { index, option ->
                val chosen = option.key == selected.key
                val background by animateColorAsState(if (chosen) colors.accentSoft else Color.Transparent, tween(AniMixMotion.Standard))
                Row(Modifier.fillMaxWidth().background(background).semantics { this.selected = chosen }
                    .clickable(role = Role.RadioButton) { onSelect(option.key); open = false; filter = "" }
                    .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(18.dp), tint = if (chosen) colors.accent else colors.textMuted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(option.title, AniMixTheme.type.bodyStrong, maxLines = 2)
                        Txt(option.subtitle, AniMixTheme.type.micro, maxLines = 2)
                    }
                    if (chosen) Icon(AniMixIcons.Check, null, Modifier.size(18.dp), tint = colors.accent)
                }
                if (index < shown.lastIndex) {
                    Box(Modifier.fillMaxWidth().padding(start = 46.dp).height(1.dp).background(colors.outline.copy(alpha = 0.5f)))
                }
            }
        }
    }
}

/** Surface and touch feedback shared by online episode cells and offline episode cards. */
@Composable
fun EpisodeCardSurface(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(if (selected) colors.accentSoft else colors.chip, tween(AniMixMotion.Standard))
    Box(modifier.then(if (onClick != null) Modifier.pressScale(interaction) else Modifier).clip(AniMixTheme.shapes.medium).background(background)
        .rim(AniMixTheme.shapes.medium, accent = selected)
        .then(if (onClick != null) Modifier.clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, enabled = enabled, onClick = onClick) else Modifier), content = content)
}
