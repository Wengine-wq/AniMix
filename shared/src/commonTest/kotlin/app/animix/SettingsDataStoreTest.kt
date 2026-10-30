package app.animix

import app.animix.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class SettingsDataStoreTest {
    private fun disk() = DiskStore("build/test-preferences-${Random.nextInt(1_000_000_000)}")

    @Test fun migratesEveryLegacyFieldAndPreservesTheOriginalBackup() = runTest {
        val disk = disk()
        val expected = Settings(themeMode = ThemeMode.Dawn, skipOpenings = false, skipEndings = true,
            autoNextEpisode = false, autoNextAsked = true, favoritesLast = true, smartConnection = false,
            trafficSaver = false, animationsEnabled = false, batterySaver = true, preferredDubbing = "studio",
            preferredQuality = "1080p", fillScreen = true, downloadQuality = "720p",
            dubbingByAnime = mapOf("s52991" to "dubbing-1", "s16498" to "dubbing-2"))
        val legacy = AppJson.encodeToString(Settings.serializer(), expected)
        disk.writeChecked("settings.json", legacy)
        val settings = AppSettings(disk, backgroundScope)
        settings.load()
        settings.flush()
        assertEquals(expected, settings.state.value)
        assertEquals(legacy, disk.readChecked("settings.json"))
        assertNotNull(disk.readChecked("settings.preferences_pb"))
        assertEquals(expected, AppSettings(disk, backgroundScope).apply { load() }.state.value)
    }

    @Test fun newInstallsStartWithAutoNextAndSkipsOffButKeepExistingChoices() = runTest {
        val fresh = AppSettings(disk(), backgroundScope).apply { load() }.state.value
        assertFalse(fresh.autoNextEpisode)
        assertFalse(fresh.skipOpenings)
        assertFalse(fresh.skipEndings)

        val disk = disk()
        val chosen = AppSettings(disk, backgroundScope).apply { load() }
        chosen.update { it.copy(autoNextEpisode = true, autoNextAsked = true, skipOpenings = true) }
        chosen.flush()
        val restored = AppSettings(disk, backgroundScope).apply { load() }.state.value
        assertTrue(restored.autoNextEpisode)
        assertTrue(restored.skipOpenings)
    }

    @Test fun legacyJsonCannotOverrideNewPreferencesOnRestart() = runTest {
        val disk = disk()
        disk.writeChecked("settings.json", "{\"themeMode\":\"Dawn\",\"skipEndings\":true}")
        val settings = AppSettings(disk, backgroundScope).apply { load() }
        settings.update { it.copy(themeMode = ThemeMode.Dusk, skipEndings = false, preferredQuality = "1080p") }
        settings.flush()
        disk.writeChecked("settings.json", "{\"themeMode\":\"Dawn\",\"skipEndings\":true}")
        val restored = AppSettings(disk, backgroundScope).apply { load() }.state.value
        assertEquals(ThemeMode.Dusk, restored.themeMode)
        assertFalse(restored.skipEndings)
        assertEquals("1080p", restored.preferredQuality)
    }

    @Test fun startupEditsApplyToTheMigratedSettingsInOrder() = runTest {
        val disk = disk()
        disk.writeChecked("settings.json", "{\"themeMode\":\"Dawn\",\"preferredQuality\":\"720p\",\"dubbingByAnime\":{\"s1\":\"first\"}}")
        val settings = AppSettings(disk, backgroundScope)
        settings.update { it.copy(skipEndings = true, dubbingByAnime = it.dubbingByAnime + ("s2" to "second")) }
        settings.update { it.copy(batterySaver = true) }
        assertTrue(settings.state.value.batterySaver, "UI must respond before the disk write")
        settings.flush()
        assertEquals(ThemeMode.Dawn, settings.state.value.themeMode)
        assertEquals("720p", settings.state.value.preferredQuality)
        assertEquals(mapOf("s1" to "first", "s2" to "second"), settings.state.value.dubbingByAnime)
        assertTrue(settings.state.value.skipEndings)
        assertTrue(settings.state.value.batterySaver)
    }

    @Test fun rapidTogglesAndNullablePreferencesPersistExactly() = runTest {
        val disk = disk()
        val settings = AppSettings(disk, backgroundScope)
        repeat(51) { settings.update { it.copy(skipEndings = !it.skipEndings) } }
        settings.update { it.copy(preferredDubbing = "Studio", downloadQuality = "1080p") }
        settings.update { it.copy(preferredDubbing = null, downloadQuality = null) }
        settings.flush()
        val restored = AppSettings(disk, backgroundScope).apply { load() }.state.value
        assertTrue(restored.skipEndings)
        assertNull(restored.preferredDubbing)
        assertNull(restored.downloadQuality)
        settings.load()
        assertEquals(restored, settings.state.value)
    }

    @Test fun concurrentEditsAndSharedFileInstancesLoseNoPreferences() = runTest {
        val disk = disk()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = AppSettings(disk, scope)
            val second = AppSettings(disk, scope)
            coroutineScope {
                repeat(4) { producer -> launch(Dispatchers.Default) {
                    val settings = if (producer % 2 == 0) first else second
                    repeat(20) { item -> settings.update { it.copy(dubbingByAnime = it.dubbingByAnime + ("$producer-$item" to "studio")) } }
                } }
            }
            first.update { it.copy(themeMode = ThemeMode.Dawn) }
            second.update { it.copy(batterySaver = true) }
            first.flush(); second.flush()
            val restored = AppSettings(disk, backgroundScope).apply { load() }.state.value
            assertEquals(80, restored.dubbingByAnime.size)
            assertEquals(ThemeMode.Dawn, restored.themeMode)
            assertTrue(restored.batterySaver)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun malformedLegacyBackupIsPreservedAndNotRetriedAfterMigration() = runTest {
        val disk = disk()
        disk.writeChecked("settings.json", "{broken")
        val settings = AppSettings(disk, backgroundScope).apply { load() }
        assertEquals(Settings(), settings.state.value)
        settings.update { it.copy(themeMode = ThemeMode.Dusk) }
        settings.flush()
        assertEquals("{broken", disk.readChecked("settings.json"))
        assertEquals(ThemeMode.Dusk, AppSettings(disk, backgroundScope).apply { load() }.state.value.themeMode)
    }
}
