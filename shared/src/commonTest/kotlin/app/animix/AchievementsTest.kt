package app.animix

import app.animix.core.AppSettings
import app.animix.core.DiskStore
import app.animix.data.achievements.Achievements
import app.animix.data.achievements.AchievementCatalog
import app.animix.data.library.Library
import app.animix.data.library.ListStatus
import app.animix.model.Anime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AchievementsTest {

    private fun anime(id: Int) = Anime.placeholder(id).copy(title = "Тайтл $id", episodes = 12)

    @Test
    fun catalogHasAll51UniqueIdsAndIcons() {
        assertEquals(51, AchievementCatalog.size)
        assertEquals(51, AchievementCatalog.map { it.id }.toSet().size)
        assertEquals((1..51).toList(), AchievementCatalog.map { it.number })
    }

    @Test
    fun libraryActionsUnlockAchievements() = runTest {
        val store = DiskStore("build/test-ach-${Random.nextInt(1_000_000)}")
        val library = Library(store, backgroundScope).apply { load() }
        val settings = AppSettings(store, backgroundScope)
        val achievements = Achievements(store, backgroundScope, library, settings).apply { load() }

        library.toggleFavorite(anime(1))
        library.setStatus(anime(16498), ListStatus.Completed) // Attack on Titan
        testScheduler.runCurrent()
        library.setStatus(anime(2), ListStatus.OnHold)
        library.setStatus(anime(2), ListStatus.Watching)
        library.renameProfile("Илья")
        achievements.themeChosen("Dusk")
        testScheduler.runCurrent()

        assertEquals(51, achievements.progress.value.size, "progress never evaluated")
        val unlocked = achievements.progress.value.filter { it.unlocked }.map { it.achievement.id }.toSet()
        assertTrue("first_favorite" in unlocked, "missing first_favorite, got $unlocked")
        assertTrue("attack_on_titan" in unlocked, "missing attack_on_titan, got $unlocked")
        assertTrue("first_completed" in unlocked, "missing first_completed, got $unlocked")
        assertTrue("return_from_hold" in unlocked, "missing return_from_hold, got $unlocked")
        assertTrue("custom_profile_name" in unlocked, "missing custom_profile_name, got $unlocked")
        assertTrue("dark_theme_selected" in unlocked, "missing dark_theme_selected, got $unlocked")
        assertFalse("ten_completed" in unlocked)
        store.clear()
    }

    @Test
    fun streaksCountConsecutiveDays() {
        assertEquals(3, Achievements.longestStreak(setOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-05")))
        assertEquals(0, Achievements.longestStreak(emptySet()))
    }
}
