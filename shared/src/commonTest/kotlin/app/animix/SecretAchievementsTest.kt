package app.animix

import app.animix.core.*
import app.animix.data.achievements.*
import app.animix.data.library.*
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class SecretAchievementsTest {
    private fun disk() = DiskStore("build/test-secret-${Random.nextInt(1_000_000)}")

    @Test fun healerSearchIgnoresCaseSpacingAndHyphens() {
        for (text in listOf("Маг целитель", "маг-целитель", "  МАГ   ЦЕЛИТЕЛЬ: новый старт", "маг–целитель")) {
            assertTrue(Achievements.isHealerSearch(text), text)
        }
        assertFalse(Achievements.isHealerSearch("маг"))
        assertFalse(Achievements.isHealerSearch("целитель"))
    }

    @Test fun secretGenresAreRecognisedByIdOrName() {
        assertEquals("hentai", Achievements.secretGenre("12", "Хентай"))
        assertEquals("ecchi", Achievements.secretGenre("x", "Этти"))
        assertNull(Achievements.secretGenre("1", "Экшен"))
    }

    @Test fun secretsUnlockOnceAndStayHiddenUntilThen() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        rewards.flush()
        val secrets = rewards.progress.value.filter { it.achievement.secret }
        assertEquals(3, secrets.size)
        assertTrue(secrets.none { it.unlocked })

        rewards.genreChosen("1", "Экшен")
        rewards.searched("наруто")
        rewards.flush()
        assertTrue(rewards.progress.value.filter { it.achievement.secret }.none { it.unlocked })

        rewards.genreChosen("12", "Хентай")
        rewards.genreChosen("9", "Этти")
        rewards.searched("маг-целитель")
        rewards.flush()
        val earned = rewards.progress.value.filter { it.achievement.secret && it.earned }.map { it.achievement.id }.toSet()
        assertEquals(setOf("secret_hentai_filter", "secret_ecchi_filter", "secret_redo_healer"), earned)
        val first = rewards.progress.value.first { it.achievement.id == "secret_redo_healer" }.unlockedAt
        repeat(5) { rewards.searched("Маг целитель") }
        rewards.flush()
        assertEquals(first, rewards.progress.value.first { it.achievement.id == "secret_redo_healer" }.unlockedAt)
    }
}
