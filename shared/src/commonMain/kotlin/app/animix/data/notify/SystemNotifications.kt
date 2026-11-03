package app.animix.data.notify

/** Shows a notice outside the app: an Android notification or a Windows toast from the tray. */
expect object SystemNotifications {
    fun show(notice: EpisodeNotice)
}

/** The link a notice opens: the episode on the watch screen, or the page of a YummyAnime-only title. */
internal fun EpisodeNotice.link(): String =
    if (anime.source == app.animix.model.AnimeSource.Shikimori) "animix://watch/${anime.id}/$episode" else "animix://yummy/${anime.id}"

internal fun EpisodeNotice.text(): Pair<String, String> =
    anime.title to (if (dubbing != null) "Серия $episode вышла в озвучке $dubbing" else "Вышла серия $episode")
