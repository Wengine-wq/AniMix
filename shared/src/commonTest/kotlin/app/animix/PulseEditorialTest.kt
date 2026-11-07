package app.animix

import app.animix.data.library.LibraryData
import app.animix.data.pulse.*
import app.animix.model.NewsItem
import kotlin.test.*

class PulseEditorialTest {
    private val now = kotlin.time.Instant.parse("2026-11-07T12:00:00Z").toEpochMilliseconds()

    @Test
    fun summaryTellsTheStoryNotTheHeadlineOrBoilerplate() {
        val summary = storySummary(
            "Анонс второго сезона «Фрирен»",
            "Анонс второго сезона «Фрирен». Студия Madhouse вернётся к работе над сериалом, премьера запланирована на январь. " +
                "Режиссёром снова станет Кэйитиро Сайто. Источник: официальный сайт.",
        )
        assertTrue(summary.startsWith("Студия Madhouse"), summary)
        assertFalse("Источник" in summary, summary)
        assertTrue(summary.endsWith("."), summary)
        // One endless sentence is cut at a word with an ellipsis, never mid-word.
        val long = storySummary("x", "слово ".repeat(120), limit = 120)
        assertTrue(long.length <= 121 && long.endsWith("…"), long)
        assertEquals("", storySummary("x", "   "))
    }

    @Test
    fun theLeadIsTheMostInterestingStoryWithAPicture() {
        val plain = NewsItem(1, "Рейтинг продаж дисков", "Короткая заметка без подробностей.", "2026-11-06T10:00:00Z", "Новость", url = "a")
        val announce = NewsItem(2, "Анонс продолжения «Атаки титанов»", "Студия MAPPA сообщила о новом фильме. Премьера осенью.", "2026-11-05T10:00:00Z",
            "Анонс", imageUrl = "https://example/x.jpg", url = "b")
        val trailer = NewsItem(3, "Трейлер «Пятерняшек»", "Вышел новый ролик. В нём показали героинь.", "2026-11-06T09:00:00Z", "Трейлер", url = "c")
        val (lead, more, rest) = pickStories(listOf(plain, announce, trailer), emptySet(), now)
        assertEquals(2L, lead?.item?.id)
        assertEquals(listOf(3L, 1L), more.map { it.item.id })
        assertTrue(rest.isEmpty())
        assertNull(pickStories(emptyList(), emptySet(), now).first)
    }

    @Test
    fun introReadsAsSentencesFromFacts() {
        val announce = NewsItem(2, "Анонс продолжения «Атаки титанов»", "Студия MAPPA сообщила о новом фильме.", "2026-11-05T10:00:00Z", "Анонс", url = "b")
        val trailer = NewsItem(3, "Трейлер «Пятерняшек»", "Вышел новый ролик.", "2026-11-06T09:00:00Z", "Трейлер", url = "c")
        val digest = digestFrom(LibraryData(), news = listOf(announce, trailer))
        val issue = issueFrom(digest, emptySet(), now)
        assertTrue(issue.intro.startsWith("Главное на этой неделе: Анонс продолжения «Атаки титанов»."), issue.intro)
        assertTrue("А ещё за неделю — трейлер." in issue.intro, issue.intro)
        // An empty week still says something useful instead of an empty paragraph.
        assertTrue(issueFrom(digestFrom(LibraryData()), emptySet(), now).intro.isNotBlank())
    }
}
