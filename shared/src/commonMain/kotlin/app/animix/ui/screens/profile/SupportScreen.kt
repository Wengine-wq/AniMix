package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.Platform
import app.animix.data.AppRelease
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.theme.AniMixTheme

/**
 * Поддержка as a chat with the AniMix team. Messages need the AniMix server, which is not
 * running yet: the chat shows that plainly and keeps the input closed instead of pretending to send.
 */
@Composable
fun SupportScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val colors = AniMixTheme.colors
    var draft by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxSize()
                .padding(contentPadding).padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(AniMixIcons.Back, onClick = { navigator.back() }, contentDescription = "Назад")
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                    Icon(AniMixIcons.Help, null, Modifier.size(20.dp), tint = colors.onAccent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt("Поддержка AniMix", AniMixTheme.type.heading, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.textMuted))
                        Spacer(Modifier.width(6.dp))
                        Txt("Недоступна: нет серверной части", AniMixTheme.type.micro)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Bubble(
                    "Здравствуйте! Здесь будет чат с командой AniMix: вопросы, ошибки, пожелания. " +
                        "Он заработает вместе с сервером и аккаунтами AniMix.",
                )
                Bubble(
                    "Когда чат включится, к сообщению можно будет приложить данные об устройстве. Сейчас это: " +
                        "${AppRelease.label}, ${Platform.name}.",
                )
            }
            Spacer(Modifier.height(12.dp))
            // The input stays visible but closed, so it is clear where the chat will be.
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = draft, onValueChange = { draft = it }, enabled = false, singleLine = true,
                    textStyle = AniMixTheme.type.body.copy(color = colors.textMuted), cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.weight(1f).height(48.dp).clip(AniMixTheme.shapes.pill).background(colors.chip),
                    decorationBox = { field ->
                        Box(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                            Txt("Чат пока недоступен", AniMixTheme.type.body, color = colors.textMuted)
                            field()
                        }
                    },
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(48.dp).clip(CircleShape).background(colors.chip), contentAlignment = Alignment.Center) {
                    Icon(AniMixIcons.Share, "Отправить недоступно", Modifier.size(20.dp), tint = colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String) {
    val colors = AniMixTheme.colors
    Box(
        Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 22.dp))
            .background(colors.surfaceRaised).rim(AniMixTheme.shapes.large, accent = false).padding(horizontal = 16.dp, vertical = 12.dp),
    ) { Txt(text, AniMixTheme.type.body) }
}
