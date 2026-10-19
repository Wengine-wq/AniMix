package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.Storage
import app.animix.data.StorageGroup
import app.animix.data.StorageUsage
import app.animix.data.formatBytes
import app.animix.ui.components.*
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Tab
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.launch

/** Settings → «Данные и кэш»: what takes space, how much in all, and what each clearing removes. */
@Composable
internal fun StorageSection(graph: AppGraph, navigator: Navigator) {
    val colors = AniMixTheme.colors
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<StorageGroup?>(null) }
    var freed by remember { mutableStateOf<Pair<StorageGroup, Long>?>(null) }
    LaunchedEffect(revision) { usage = Storage.usage() }
    val palette = mapOf(
        StorageGroup.Downloads to colors.accent,
        StorageGroup.Images to colors.accentStrong,
        StorageGroup.Catalog to colors.star,
        StorageGroup.Browser to colors.textSecondary,
        StorageGroup.Data to colors.textMuted,
    )
    val current = usage
    Column {
        Txt(current?.let { "AniMix занимает ${formatBytes(it.total)}" } ?: "Считаем…", AniMixTheme.type.title)
        Spacer(Modifier.height(12.dp))
        // One bar split by group, so the biggest part is visible at a glance.
        Row(Modifier.fillMaxWidth().height(10.dp).clip(AniMixTheme.shapes.pill).background(colors.outline)) {
            if (current != null && current.total > 0) StorageGroup.entries.forEach { group ->
                val share = (current.bytes[group] ?: 0).toFloat() / current.total
                if (share > 0.003f) Box(Modifier.weight(share).fillMaxHeight().background(palette.getValue(group)))
            }
        }
        Spacer(Modifier.height(20.dp))
        Panel {
            StorageGroup.entries.forEachIndexed { index, group ->
                val size = current?.bytes?.get(group)
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(palette.getValue(group)))
                        Spacer(Modifier.width(10.dp))
                        Txt(group.title, AniMixTheme.type.bodyStrong, modifier = Modifier.weight(1f))
                        Txt(size?.let(::formatBytes) ?: "…", AniMixTheme.type.label)
                    }
                    Txt(group.description, AniMixTheme.type.caption, modifier = Modifier.padding(top = 4.dp, start = 20.dp))
                    when {
                        group == StorageGroup.Downloads && (size ?: 0) > 0 -> Row(Modifier.padding(top = 10.dp, start = 20.dp)) {
                            Chip("Открыть загрузки", false, { navigator.select(Tab.Downloads) })
                        }
                        group.clearable && confirm == group -> Column(Modifier.padding(top = 10.dp, start = 20.dp)) {
                            Txt("Удалить «${group.title.lowercase()}» (${size?.let(::formatBytes) ?: "…"})?", AniMixTheme.type.label)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("Отмена", false, { confirm = null })
                                Chip("Очистить", true, {
                                    confirm = null
                                    scope.launch {
                                        if (group == StorageGroup.Catalog) graph.clearCache()
                                        freed = group to Storage.clear(group)
                                        revision++
                                    }
                                }, icon = app.animix.ui.icons.AniMixIcons.Trash)
                            }
                        }
                        group.clearable && (size ?: 0) > 0 -> Row(Modifier.padding(top = 10.dp, start = 20.dp)) {
                            Chip("Очистить", false, { confirm = group; freed = null }, icon = app.animix.ui.icons.AniMixIcons.Trash)
                        }
                    }
                    freed?.takeIf { it.first == group }?.let { (_, bytes) ->
                        Txt("Освобождено ${formatBytes(bytes)}", AniMixTheme.type.micro, color = colors.accentText, modifier = Modifier.padding(top = 8.dp, start = 20.dp))
                    }
                }
                if (index < StorageGroup.entries.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 36.dp).height(1.dp).background(colors.outline))
            }
        }
        Spacer(Modifier.height(10.dp))
        Txt("Очистка кэша не трогает загрузки, списки и настройки.", AniMixTheme.type.micro, color = Color.Unspecified)
    }
}
