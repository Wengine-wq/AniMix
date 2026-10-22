package app.animix.data.downloads

/** Continue through available downloads, keeping the dub and preferring the current quality. */
internal fun nextDownloadedEpisode(
    items: List<DownloadItem>,
    current: DownloadItem,
    available: (DownloadItem) -> Boolean,
): DownloadItem? = items.asSequence()
    .filter { it.status == DownloadStatus.Completed && it.anime.key == current.anime.key &&
        it.dubbingKey == current.dubbingKey && it.episode.sortKey > current.episode.sortKey }
    .filter(available)
    .minWithOrNull(compareBy<DownloadItem> { it.episode.sortKey }
        .thenBy { if (it.quality == current.quality) 0 else 1 }
        .thenBy { it.id })
