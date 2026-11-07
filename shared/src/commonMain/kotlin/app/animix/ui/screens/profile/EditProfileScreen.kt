package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.AppJson
import app.animix.core.characterCount
import app.animix.core.takeCharacters
import app.animix.data.library.ProfileDraft
import app.animix.data.library.ProfileMedia
import app.animix.data.library.ProfileNameLimit
import app.animix.data.library.level
import androidx.compose.ui.draw.alpha
import app.animix.data.profile.ProfileImageKind
import app.animix.data.profile.discardProfileImages
import app.animix.data.profile.rememberProfileImagePicker
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.launch

private val ProfileDraftSaver = Saver<ProfileDraft, String>(
    save = { AppJson.encodeToString(ProfileDraft.serializer(), it) },
    restore = { AppJson.decodeFromString(ProfileDraft.serializer(), it) },
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun EditProfileScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    var draft by rememberSaveable(stateSaver = ProfileDraftSaver) { mutableStateOf(library.profile.draft()) }
    var createdFiles by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val picked = rememberProfileImagePicker(onPicked = { kind, media ->
        media.localFileName?.let { createdFiles = createdFiles + it }
        draft = if (kind == ProfileImageKind.Avatar) draft.copy(avatar = media) else draft.copy(banner = media)
        problem = null
    }, onError = { problem = it })
    val unlocked = achievements.filter { it.unlocked }
    val badge = unlocked.firstOrNull { it.achievement.id == draft.badgeAchievementId }
    val level by graph.level.collectAsState()
    fun close() {
        val keep = listOfNotNull(library.profile.avatar?.localFileName, library.profile.banner?.localFileName).toSet()
        val discarded = createdFiles.map { ProfileMedia(localFileName = it) }
        graph.scope.launch { discardProfileImages(discarded, keep) }
        navigator.back()
    }
    BackHandler(onBack = ::close)
    ScreenColumn(contentPadding) {
        PageHeader("Редактировать профиль", ::close, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        // A locked design or frame can be tried on here: the preview shows it, saving keeps the open choice.
        var tryDesign by remember { mutableStateOf<String?>(null) }
        var tryFrame by remember { mutableStateOf<String?>(null) }
        val shownDesign = tryDesign ?: draft.design
        val shownFrame = tryFrame ?: draft.frame
        Box {
            ProfileDesignLayer(profileDesign(shownDesign), Modifier.matchParentSize(), animated = true)
            ProfileIdentity(library.profile.copy(name = draft.name.ifBlank { library.profile.name }, about = draft.about,
                avatar = draft.avatar, banner = draft.banner, design = shownDesign, frame = shownFrame), badge, level = level)
        }
        if (tryDesign != null || tryFrame != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AniMixIcons.Sparkle, null, Modifier.size(16.dp), tint = AniMixTheme.colors.accent)
                Spacer(Modifier.width(8.dp))
                val unlockAt = if (tryFrame != null) FrameLevel else DesignLevel
                Txt("Примерка — так профиль увидят другие с ${unlockAt}-го уровня. Не сохраняется.", AniMixTheme.type.caption, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Chip("Снять", false, { tryDesign = null; tryFrame = null })
            }
        }
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Аватар", false, { if (!picked.busy) picked.open(ProfileImageKind.Avatar) }, icon = AniMixIcons.User)
            Chip("Баннер", false, { if (!picked.busy) picked.open(ProfileImageKind.Banner) }, icon = AniMixIcons.Image)
            if (draft.avatar != null) Chip("Убрать аватар", false, { draft = draft.copy(avatar = null) })
            if (draft.banner != null) Chip("Убрать баннер", false, { draft = draft.copy(banner = null) })
        }
        Spacer(Modifier.height(8.dp))
        Txt(if (picked.busy) "Подготавливаем изображение…" else "Фото обрезается по центру. Предпросмотр показывает результат.", AniMixTheme.type.micro)
        problem?.let { Txt(it, AniMixTheme.type.caption, color = AniMixTheme.colors.danger, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(24.dp))
        ProfileField("Имя", draft.name, { draft = draft.copy(name = it.takeCharacters(ProfileNameLimit)) })
        Txt("${draft.name.characterCount()} / $ProfileNameLimit", AniMixTheme.type.micro, modifier = Modifier.align(Alignment.End).padding(top = 6.dp))
        Spacer(Modifier.height(20.dp))
        ProfileField("О себе", draft.about, { draft = draft.copy(about = it.takeCharacters(160)) }, multiline = true)
        Txt("${draft.about.characterCount()} / 160", AniMixTheme.type.micro, modifier = Modifier.align(Alignment.End).padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        Txt("Значок рядом с именем", AniMixTheme.type.heading)
        Spacer(Modifier.height(6.dp))
        Txt("Выберите одно из полученных достижений, в том числе из истории просмотров.", AniMixTheme.type.caption)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BadgeOption(draft.badgeAchievementId == null, "Без значка", { draft = draft.copy(badgeAchievementId = null) }) {
                    Icon(AniMixIcons.Close, null, Modifier.size(24.dp), tint = AniMixTheme.colors.textSecondary)
                }
            }
            items(unlocked, key = { it.achievement.id }) { progress ->
                BadgeOption(progress.achievement.id == draft.badgeAchievementId, progress.achievement.name,
                    { draft = draft.copy(badgeAchievementId = progress.achievement.id) }) {
                    AchievementIcon(progress.achievement, true, 48.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Txt(badge?.achievement?.name ?: if (unlocked.isEmpty()) "Значки появятся после первого достижения." else "Без значка",
            AniMixTheme.type.caption)
        Spacer(Modifier.height(28.dp))
        RewardPicker("Оформление профиля", DesignLevel, level.level, draft.design, { draft = draft.copy(design = it); tryDesign = null }, tryDesign, { tryDesign = it }) { design ->
            ProfileDesignLayer(design, Modifier.fillMaxSize(), fade = false)
        }
        Spacer(Modifier.height(24.dp))
        RewardPicker("Рамка аватара", FrameLevel, level.level, draft.frame, { draft = draft.copy(frame = it); tryFrame = null }, tryFrame, { tryFrame = it }) { design ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AniMixTheme.colors.accentGradient))
                ProfileFrameLayer(design, Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton("Сохранить профиль", {
            val previousMedia = listOfNotNull(library.profile.avatar, library.profile.banner) + createdFiles.map { ProfileMedia(localFileName = it) }
            graph.library.updateProfile(draft, unlocked.map { it.achievement.id }.toSet())
            val keep = listOfNotNull(draft.avatar?.localFileName, draft.banner?.localFileName).toSet()
            graph.scope.launch { graph.library.flush(); discardProfileImages(previousMedia, keep) }
            navigator.back()
        }, Modifier.fillMaxWidth(), icon = AniMixIcons.Check, enabled = draft.name.isNotBlank() && !picked.busy)
    }
}

/**
 * A level reward: «Без» and the four designs as small previews. Until [level] the choice is shown but
 * locked, with how many levels remain, so the reward is something to look forward to.
 */
@Composable
private fun RewardPicker(title: String, level: Int, current: Int, selected: String?, onSelect: (String?) -> Unit,
    trying: String?, onTry: (String?) -> Unit, preview: @Composable (ProfileDesign) -> Unit) {
    val colors = AniMixTheme.colors
    val open = current >= level
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(title, AniMixTheme.type.heading, modifier = Modifier.weight(1f))
        if (!open) {
            Icon(AniMixIcons.Lock, null, Modifier.size(14.dp), tint = colors.textMuted)
            Spacer(Modifier.width(6.dp))
            Txt("С $level уровня", AniMixTheme.type.caption)
        }
    }
    Spacer(Modifier.height(6.dp))
    Txt(if (open) "Награда за уровень $level. Видна вам и всем, кто откроет ваш профиль."
        else "Откроется на уровне $level — осталось ${level - current} ${levelsWord(level - current)}. Нажмите, чтобы примерить.", AniMixTheme.type.caption)
    Spacer(Modifier.height(12.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            RewardOption(selected == null, "Без", open = true, { onSelect(null) }) {
                Icon(AniMixIcons.Close, null, Modifier.size(20.dp), tint = colors.textSecondary)
            }
        }
        items(ProfileDesign.entries.toList(), key = { it.id }) { design ->
            RewardOption(selected == design.id || trying == design.id, design.label, open,
                { if (open) onSelect(design.id) else onTry(design.id.takeIf { it != trying }) }) { preview(design) }
        }
    }
}

@Composable
private fun RewardOption(selected: Boolean, label: String, open: Boolean, onSelect: () -> Unit, content: @Composable () -> Unit) {
    val colors = AniMixTheme.colors
    val shape = AniMixTheme.shapes.medium
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 96.dp, height = 64.dp).clip(shape).background(colors.chip)
            .border(1.dp, if (selected) colors.accent else colors.outline, shape)
            .semantics { contentDescription = if (open) label else "$label, пока закрыто, примерить" }
            .selectable(selected, onClick = onSelect, role = Role.RadioButton),
            contentAlignment = Alignment.Center) {
            // A closed reward is shown in full, so it is worth waiting for; the lock sits in the corner, off the design.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
            if (!open) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(colors.background.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
                Icon(AniMixIcons.Lock, null, Modifier.size(12.dp), tint = colors.textSecondary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Txt(label, AniMixTheme.type.label, color = if (selected) colors.textPrimary else colors.textSecondary, maxLines = 1)
    }
}

internal fun levelsWord(n: Int): String = when {
    n % 100 in 11..14 -> "уровней"; n % 10 == 1 -> "уровень"; n % 10 in 2..4 -> "уровня"; else -> "уровней"
}

@Composable
private fun BadgeOption(selected: Boolean, description: String, onSelect: () -> Unit, content: @Composable () -> Unit) {
    val shape = AniMixTheme.shapes.medium
    Box(Modifier.size(68.dp).clip(shape).background(if (selected) AniMixTheme.colors.accentSoft else AniMixTheme.colors.chip)
        .border(1.dp, if (selected) AniMixTheme.colors.accent else AniMixTheme.colors.outline, shape)
        .semantics { contentDescription = description }.selectable(selected, onClick = onSelect, role = Role.RadioButton),
        contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ProfileField(label: String, value: String, onChange: (String) -> Unit, multiline: Boolean = false) {
    val colors = AniMixTheme.colors
    Txt(label, AniMixTheme.type.label)
    Spacer(Modifier.height(8.dp))
    BasicTextField(value, onChange, singleLine = !multiline, minLines = if (multiline) 3 else 1, maxLines = if (multiline) 5 else 1,
        textStyle = AniMixTheme.type.body.copy(color = colors.textPrimary), cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = if (multiline) ImeAction.Default else ImeAction.Next),
        modifier = Modifier.fillMaxWidth().clip(AniMixTheme.shapes.medium).background(colors.chip)
            .rim(AniMixTheme.shapes.medium, accent = false).semantics { contentDescription = label }.padding(16.dp))
}
