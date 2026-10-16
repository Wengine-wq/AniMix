package app.animix.ui.screens.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.Review
import app.animix.model.ReviewSource
import app.animix.ui.components.Chip
import app.animix.ui.components.Expandable
import app.animix.ui.components.RichTextView
import app.animix.ui.components.IconCircle
import app.animix.ui.components.Load
import app.animix.ui.components.MessageState
import app.animix.ui.components.Panel
import app.animix.ui.components.RemoteImage
import app.animix.ui.components.Shimmer
import app.animix.ui.components.Txt
import app.animix.ui.components.formatScore
import app.animix.ui.components.rememberLoad
import app.animix.ui.components.userMessage
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation

/** One review or comment. Long texts fold to a few lines until tapped. */
@Composable
fun ReviewCard(review: Review, modifier: Modifier = Modifier, collapsedLines: Int = 6) {
    val colors = AniMixTheme.colors
    val type = AniMixTheme.type
    Panel(modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(colors.surfaceRaised), contentAlignment = Alignment.Center) {
                    if (review.avatarUrl != null) {
                        RemoteImage(review.avatarUrl, Modifier.size(36.dp), shape = CircleShape)
                    } else {
                        Icon(AniMixIcons.User, null, Modifier.size(18.dp), tint = colors.textMuted)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(review.author.ifBlank { "Аноним" }, type.bodyStrong, maxLines = 1)
                    Txt("${review.source.label} · ${review.date}", type.micro, maxLines = 1)
                }
                review.score?.let {
                    Row(
                        Modifier.clip(AniMixTheme.shapes.pill).background(colors.accentSoft).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AniMixIcons.Star, null, Modifier.size(12.dp), tint = colors.star)
                        Spacer(Modifier.width(4.dp))
                        Txt(formatScore(it), type.label, color = colors.accentText)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Expandable(collapsedHeight = (collapsedLines * 21).dp, fadeColor = colors.surface) { RichTextView(review.body) }
            review.likes?.takeIf { it > 0 }?.let {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AniMixIcons.Heart, null, Modifier.size(14.dp), tint = colors.textMuted)
                    Spacer(Modifier.width(4.dp))
                    Txt(it.toString(), type.micro)
                }
            }
        }
    }
}

/** Preview on the anime page: a couple of YummyAnime reviews and a way to read everything. */
@Composable
fun ReviewsPreview(graph: AppGraph, anime: Anime, detail: AnimeDetail?, onOpenAll: () -> Unit) {
    // Wait for the titles: a deep link starts with only an id, and YummyAnime is searched by title.
    val reviews = rememberLoad(anime.key to anime.title.isNotEmpty()) {
        if (anime.title.isEmpty()) awaitCancellation()
        graph.anime.yummyReviews(anime)
    }
    val type = AniMixTheme.type
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt("Отзывы", type.heading, modifier = Modifier.weight(1f))
        Txt(
            "Все",
            type.label,
            color = AniMixTheme.colors.accent,
            modifier = Modifier.clip(AniMixTheme.shapes.pill).clickable(onClick = onOpenAll).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    when (val state = reviews.value) {
        Load.Loading -> Shimmer(Modifier.fillMaxWidth().height(120.dp), AniMixTheme.shapes.large)
        is Load.Failed -> Txt("Отзывы YummyAnime не загрузились: ${state.error.userMessage()}", type.caption)
        is Load.Ready -> {
            val list = state.value
            if (list.isEmpty()) {
                val comments = detail?.commentsCount ?: 0
                Txt(
                    if (comments > 0) "На YummyAnime отзывов пока нет. На Shikimori $comments комментариев: откройте «Все»."
                    else "Отзывов пока нет",
                    type.caption,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.take(2).forEach { ReviewCard(it, collapsedLines = 4) }
                }
            }
        }
    }
}

/** All reviews: YummyAnime reviews, and the anime's discussion on Shikimori. */
@Composable
fun ReviewsScreen(graph: AppGraph, navigator: Navigator, anime: Anime, contentPadding: PaddingValues) {
    var source by rememberSaveable { mutableStateOf(ReviewSource.Yummy) }
    val detail = rememberLoad(anime.key) { graph.anime.detail(anime) }
    val ready = (detail.value as? Load.Ready)?.value
    // A link may only carry the id; YummyAnime lookup needs the titles, so use the loaded entry.
    val full = ready?.anime ?: anime.takeIf { it.title.isNotEmpty() }
    val yummy = rememberLoad(full?.key) {
        if (full == null) awaitCancellation()
        graph.anime.yummyReviews(full)
    }
    val discussion = remember(anime.key) { DiscussionPaging(graph) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(ready, source) {
        if (source == ReviewSource.Shikimori && ready != null) discussion.start(ready)
    }
    LaunchedEffect(list, source) {
        if (source != ReviewSource.Shikimori) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= list.layoutInfo.totalItemsCount - 4) scope.launch { discussion.loadNext() } }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = list,
            modifier = Modifier.widthIn(max = 820.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconCircle(AniMixIcons.Back, onClick = { navigator.back() })
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Txt("Отзывы", AniMixTheme.type.title)
                            Txt(ready?.anime?.title ?: anime.title, AniMixTheme.type.caption, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val yummyCount = (yummy.value as? Load.Ready)?.value?.size
                        Chip(
                            "YummyAnime" + (yummyCount?.let { " · $it" } ?: ""),
                            selected = source == ReviewSource.Yummy,
                            onClick = { source = ReviewSource.Yummy },
                        )
                        if (anime.source == app.animix.model.AnimeSource.Shikimori) {
                            Chip(
                                "Обсуждение Shikimori" + (ready?.commentsCount?.takeIf { it > 0 }?.let { " · $it" } ?: ""),
                                selected = source == ReviewSource.Shikimori,
                                onClick = { source = ReviewSource.Shikimori },
                            )
                        }
                    }
                }
            }
            when (source) {
                ReviewSource.Yummy -> when (val state = yummy.value) {
                    Load.Loading -> items(3) { Shimmer(Modifier.fillMaxWidth().height(140.dp), AniMixTheme.shapes.large) }
                    is Load.Failed -> item { MessageState("Не удалось загрузить", message = state.error.userMessage(), icon = AniMixIcons.Refresh, actionLabel = "Повторить", onAction = yummy::refresh) }
                    is Load.Ready -> if (state.value.isEmpty()) {
                        item { MessageState("На YummyAnime отзывов пока нет", icon = AniMixIcons.Help) }
                    } else {
                        items(state.value, key = { it.id }) { ReviewCard(it) }
                    }
                }
                ReviewSource.Shikimori -> {
                    items(discussion.items, key = { it.id }) { ReviewCard(it) }
                    when {
                        discussion.error != null && discussion.items.isEmpty() -> item {
                            MessageState("Не удалось загрузить", message = discussion.error?.userMessage(), icon = AniMixIcons.Refresh, actionLabel = "Повторить", onAction = { scope.launch { discussion.loadNext() } })
                        }
                        discussion.loading -> items(2) { Shimmer(Modifier.fillMaxWidth().height(110.dp), AniMixTheme.shapes.large) }
                        discussion.endReached && discussion.items.isEmpty() -> item { MessageState("Комментариев пока нет", icon = AniMixIcons.Help) }
                    }
                }
            }
        }
    }
}

private class DiscussionPaging(private val graph: AppGraph) {
    val items = mutableStateListOf<Review>()
    var loading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    private var detail: AnimeDetail? = null
    private var page by mutableIntStateOf(1)

    suspend fun start(detail: AnimeDetail) {
        if (this.detail != null) return
        this.detail = detail
        if (detail.topicId == null) endReached = true else loadNext()
    }

    suspend fun loadNext() {
        val current = detail ?: return
        if (loading || endReached) return
        loading = true
        error = null
        try {
            val result = graph.anime.shikimoriDiscussion(current, page)
            val known = items.mapTo(HashSet()) { it.id }
            items += result.filter { it.id !in known }
            if (result.isEmpty()) endReached = true
            page++
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            error = e
        } finally {
            loading = false
        }
    }
}
