package app.animix

import app.animix.core.*
import app.animix.data.library.*
import app.animix.data.pulse.*
import app.animix.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Instant

class PulseTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilliseconds()
    private val window = pulseWindow(now)
    private val anime = Anime.placeholder(38000).copy(title = "Клинок", originalTitle = "Kimetsu no Yaiba", episodes = 26)
    private val topic = PulseTitle(anime, "Смотрю", 3)
    private val proof = "Студия объявила о производстве нового проекта 2026-10-27."
    private val source = PulseSource("S1", "Объявление", "https://kimetsu.com/news/announcement.html", proof, listOf(anime.key))
    private val article = PulseArticle("news", "Объявление студии", "Подтверждённые сведения.", listOf(anime.key), listOf("S1"), "2026-10-27", proof)
    private val editorial = PulseEditorial("Ваша неделя", "Новости ваших тайтлов.", listOf(article))
    private fun disk() = DiskStore("build/test-pulse-${Random.nextInt(1_000_000_000)}")
    private fun library() = LibraryData(entries = mapOf(anime.key to LibraryEntry(anime, status = ListStatus.Watching)))
    private fun detail() = AnimeDetail(anime, RichText.Empty, 24, null, emptyList(), emptyList(), null, emptyList())

    @Test fun onlySourcedRelevantAndCorrectlyDatedEntriesCanReachTheReader() {
        val invalid = listOf(article.copy(sourceIds = listOf("invented")), article.copy(animeKeys = listOf("s999")),
            article.copy(date = "2020-01-01"), article.copy(category = "next", date = "2026-12-01"),
            article.copy(category = "fact", sourceIds = emptyList()))
        val fact = article.copy(category = "fact", date = null)
        val result = validatePulse(editorial.copy(articles = invalid + article + fact), listOf(source), listOf(topic), window)
        assertEquals(listOf(article, fact), result.articles)
        assertTrue(safePulseUrl(source.url))
        for (url in listOf("javascript:alert(1)", "http://example.com", "https://localhost/a", "https://127.0.0.1/a", "https://user:pass@example.com/", "https://example.com:444/"))
            assertFalse(safePulseUrl(url), url)
    }

    @Test fun interestsAreBoundedAndIncludePastViewingWithoutDroppedTitles() {
        val entries = (1..15).associate { id ->
            val a = anime.copy(id = id)
            a.key to LibraryEntry(a, status = if (id == 15) ListStatus.Completed else ListStatus.Watching, updatedAt = id.toLong())
        }
        val dropped = anime.copy(id = 999)
        val selected = pulseInterests(LibraryData(entries = entries + (dropped.key to LibraryEntry(dropped, status = ListStatus.Dropped, favorite = true))))
        assertEquals(4, selected.size)
        assertTrue(selected.any { it.status == ListStatus.Completed })
        assertFalse(selected.any { it.anime.id == 999 })
    }

    @Test fun weekIdsAreStableAndChangeOnMonday() {
        assertEquals(window.id, pulseWindow(now + 86_400_000).id)
        assertNotEquals(window.id, pulseWindow(now + 7 * 86_400_000L).id)
        assertEquals(7 * 86_400_000L, window.until - window.from)
    }

    @Test fun anInventedCurrentDateCannotTurnAnOldAnnouncementIntoNews() {
        val oldProof = "A stage show at AnimeJapan 2026 in March could bring an update."
        val old = source.copy(excerpt = oldProof)
        val fake = article.copy(quote = oldProof, date = "2026-10-27")
        assertTrue(validatePulse(editorial.copy(articles = listOf(fake)), listOf(old), listOf(topic), window).articles.isEmpty())
        assertEquals(setOf("2026-03-04"), pulseSourceDates("March 4, 2026"))
        assertEquals(setOf("2026-10-27"), pulseSourceDates("https://example.com/news/2026-10-27/story"))
    }

    @Test fun concurrentClicksAndReopeningTheSameWeekNeverPayForAnotherGeneration() = runTest {
        val disk = disk()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val editor = PulseEditor { _, _, _, _ -> calls++; gate.await(); PulseEdited(editorial, listOf(source)) }
        val repo = PulseRepository(disk, backgroundScope, { detail() }, editor) { now }
        repo.load()
        repo.generate(library()); repo.generate(library()); testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        repo.state.first { it.issues.isNotEmpty() }
        assertEquals(1, repo.state.value.issues.size)
        val reopened = PulseRepository(disk, backgroundScope, { detail() }, editor) { now }
        reopened.load(); reopened.generate(library()); testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        assertEquals(1, calls)
        assertEquals(repo.state.value.issues, reopened.state.value.issues)
        disk.clear()
    }

    @Test fun failedNextIssueLeavesTheArchiveAvailableOffline() = runTest {
        val disk = disk()
        val original = PulseIssue(window, now, listOf(topic), editorial, listOf(source))
        disk.writeChecked("anipulse.json", AppJson.encodeToString(PulseArchive.serializer(), PulseArchive(listOf(original))))
        val repo = PulseRepository(disk, backgroundScope, { detail() }, PulseEditor { _, _, _, _ -> throw PulseException("Нет сети") }) { now + 7 * 86_400_000L }
        repo.load(); repo.generate(library())
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        assertTrue(repo.state.value.error.orEmpty().contains("Нет сети"))
        assertEquals(listOf(original), repo.state.value.issues)
        assertEquals(listOf(original), AppJson.decodeFromString(PulseArchive.serializer(), disk.readChecked("anipulse.json")!!).issues)
        disk.clear()
    }

    @Test fun ungroundedResearchCannotBeReplacedByAModelAnswerFromMemory() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("""{"choices":[{"message":{"content":"Sounds current but has no sources","annotations":[]}}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(HttpTimeout) }
        try {
            val result = PolzaPulseClient(http, { "test-runtime-credential" }).edit(listOf(topic), window, emptyList()) {}
            assertTrue(result.editorial.articles.isEmpty())
            assertEquals(1, calls, "must not invoke the editor without any sources")
        } finally { http.close() }
    }

    @Test fun credentialsAreOnlyInAuthorizationAndPaidErrorsAreNotRetried() = runTest {
        var calls = 0
        val secret = "runtime-test-credential"
        val http = HttpClient(MockEngine { request ->
            calls++
            assertEquals("Bearer $secret", request.headers[HttpHeaders.Authorization])
            assertFalse(request.body.toByteArray().decodeToString().contains(secret))
            respond("denied", HttpStatusCode.Unauthorized)
        }) { install(HttpTimeout) }
        try {
            val error = assertFailsWith<PulseException> { PolzaPulseClient(http, { secret }).edit(listOf(topic), window, emptyList()) {} }
            assertFalse(error.message.orEmpty().contains(secret))
            assertEquals(1, calls)
        } finally { http.close() }
    }

    private fun at(day: String) = "${day}T15:00:00.000+03:00"
    private val sequel = Anime.placeholder(51019).copy(title = "Клинок: Замок", kind = AnimeKind.Movie, status = AnimeStatus.Announced)

    @Test fun shikimoriEventsBecomeDatedArticlesAndWeeklyEpisodes() {
        val topics = listOf(
            PulseTopic(anime.key, anime, AnimeTopic(1, "", "", at("2026-10-24"), "episode", 7)),
            PulseTopic(anime.key, anime, AnimeTopic(2, "", "", at("2026-10-27"), "episode", 8)),
            PulseTopic(anime.key, anime, AnimeTopic(3, "", "", at("2026-09-01"), "episode", 3)),
            PulseTopic(anime.key, sequel, AnimeTopic(4, "", "", at("2026-10-26"), "anons")),
            PulseTopic(anime.key, anime, AnimeTopic(5, "Показ фильма продлили", "Прокат в кинотеатрах продлили ещё на две недели по всей стране.", at("2026-10-25"))),
            PulseTopic("s1", anime, AnimeTopic(6, "", "", at("2026-10-26"), "released")),
        )
        val digest = pulseDigest(listOf(topic), topics, window)
        assertEquals(listOf(7, 8), digest.titles.single().weekEpisodes)
        val anons = digest.events.single()
        assertEquals("Анонс: Клинок: Замок", anons.title)
        assertEquals(listOf(anime.key), anons.animeKeys)
        assertEquals("2026-10-26", anons.date)
        assertTrue(digest.sources.first { it.id in anons.sourceIds }.url.endsWith("/animes/51019"))
        val news = digest.news.single()
        assertEquals("2026-10-25", news.date)
        assertEquals("https://shikimori.io/forum/news/5", news.url)
        assertEquals(news.id, digest.newsArticles.single().sourceIds.single())
    }

    @Test fun aKnownSourceDateConfirmsNewsWithoutADateInTheText() {
        val dated = source.copy(id = "K1", excerpt = "Прокат в кинотеатрах продлили ещё на две недели по всей стране.", date = "2026-10-25")
        val item = article.copy(sourceIds = listOf("K1"), date = "2026-10-25", quote = "Прокат в кинотеатрах продлили ещё на две недели")
        assertEquals(1, validatePulse(editorial.copy(articles = listOf(item)), listOf(dated), listOf(topic), window).articles.size)
        assertTrue(validatePulse(editorial.copy(articles = listOf(item.copy(date = "2026-10-26"))), listOf(dated), listOf(topic), window).articles.isEmpty())
    }

    @Test fun withoutAiTheIssueComesFromShikimoriAndCanBeRebuiltTheSameWeek() = runTest {
        val disk = disk()
        var calls = 0
        val editor = PulseEditor { _, _, _, _ -> calls++; throw PulseNotConnected() }
        val repo = PulseRepository(disk, backgroundScope, { detail() }, editor,
            topics = { listOf(AnimeTopic(4, "", "", at("2026-10-26"), "anons")) }) { now }
        repo.load(); repo.generate(library())
        val issue = repo.state.first { it.issues.isNotEmpty() && !it.busy }.issues.single()
        assertFalse(issue.aiEdited)
        assertEquals("ИИ-обзор на этом устройстве не подключён", issue.note)
        assertEquals(1, issue.editorial.articles.size)
        assertNull(repo.current())
        repo.generate(library())
        repo.state.first { !it.busy && calls == 2 }
        assertEquals(1, repo.state.value.issues.size)
        disk.clear()
    }

    @Test fun aPaidRunWithNothingConfirmedStillCountsForTheWeekAndSaysSo() = runTest {
        val disk = disk()
        val repo = PulseRepository(disk, backgroundScope, { detail() },
            PulseEditor { _, _, _, _ -> PulseEdited(PulseEditorial("", "", emptyList()), emptyList(), 2.0) },
            topics = { listOf(AnimeTopic(4, "", "", at("2026-10-26"), "anons")) }) { now }
        repo.load(); repo.generate(library())
        val issue = repo.state.first { it.issues.isNotEmpty() && !it.busy }.issues.single()
        assertTrue(issue.aiEdited)
        assertEquals("ИИ-обзор не нашёл подтверждённых новостей за неделю", issue.note)
        assertEquals("Неделя ваших тайтлов", issue.editorial.headline)
        assertNotNull(repo.current())
        disk.clear()
    }
}
