package app.animix.ui.screens.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.animix.ui.components.MessageState
import app.animix.ui.components.Txt
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme

@Composable
fun FavoritesScreen(contentPadding: PaddingValues) =
    EmptyTab("Избранное", AniMixIcons.Heart, "Здесь пока пусто", "Нажмите на сердечко в карточке аниме", contentPadding)

@Composable
fun DownloadsScreen(contentPadding: PaddingValues) =
    EmptyTab("Загрузки", AniMixIcons.Download, "Нет загруженных серий", "Скачанные серии можно смотреть без интернета", contentPadding)

@Composable
private fun EmptyTab(title: String, icon: ImageVector, headline: String, message: String, contentPadding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Txt(title, AniMixTheme.type.title, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            MessageState(headline, message = message, icon = icon)
        }
    }
}
