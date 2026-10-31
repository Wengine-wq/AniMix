package app.animix

import app.animix.data.achievements.AchievementCatalog
import app.animix.data.achievements.AchievementProgress
import app.animix.ui.screens.profile.progressLabel
import app.animix.ui.screens.home.releaseChanges
import kotlin.test.Test
import kotlin.test.assertEquals

class AchievementLabelTest {
    private fun progress(id: String, current: Int) = AchievementProgress(AchievementCatalog.first { it.id == id }, current, null)

    @Test fun watchTimeIsShownInHoursOnBothSides() {
        // 34 000 seconds once read as "34000 / 10 ч".
        assertEquals("9,4 / 10 ч", progressLabel(progress("ten_hours", 34_000)))
        assertEquals("0 / 100 ч", progressLabel(progress("hundred_hours", 0)))
        assertEquals("10 / 10 ч", progressLabel(progress("ten_hours", 99_999)))
        AchievementCatalog.filter { it.trigger.metric == "watch_seconds" }.forEach {
            assertEquals(true, progressLabel(AchievementProgress(it, 3_600, null)).startsWith("1 / "))
        }
    }

    @Test fun countsStayPlain() {
        val counted = AchievementCatalog.first { it.trigger.metric == "titles_completed" && it.trigger.threshold > 1 }
        assertEquals("1 / ${counted.trigger.threshold}", progressLabel(AchievementProgress(counted, 1, null)))
    }

    @Test fun releaseNotesGiveTheFirstList() {
        val notes = "Обновление.\n\n## Что нового\n- **Плеер** в стиле YouTube\n- Итоги года\n\n## Установка\n- APK"
        assertEquals(listOf("Плеер в стиле YouTube", "Итоги года"), releaseChanges(notes))
        assertEquals(emptyList(), releaseChanges("Без списка"))
    }
}
