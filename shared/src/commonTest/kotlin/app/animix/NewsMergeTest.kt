package app.animix.data.news

import app.animix.model.NewsItem
import kotlin.test.Test
import kotlin.test.assertEquals

class NewsMergeTest {
    @Test
    fun rssDatesAndDuplicateStories() {
        assertEquals("2026-11-02T15:02:15Z", rfc822("Mon, 02 Nov 26 15:02:15 +0000"))
        val shiki = NewsItem(1, "Анонс фильма-приквела аниме «Bocchi the Rock!»", "коротко", "2026-11-01T12:33:08Z", "Анонс", url = "a")
        val goha = NewsItem(2, "Анонс: «Bocchi the Rock!» вернётся фильмом", "длиннее текст новости", "2026-11-02T10:07:00Z", "Анонс",
            imageUrl = "https://images.goha.ru/x.jpg", url = "b", source = "GoHa.ru")
        val other = NewsItem(3, "Трейлер «Isshiki-san»", "", "2026-10-31T08:00:00Z", "Трейлер", url = "c")
        val merged = merge(listOf(shiki, goha, other))
        assertEquals(listOf(2L, 3L), merged.map { it.id }, "The same announcement once, the copy with a picture kept")
    }
}
