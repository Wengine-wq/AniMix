package app.animix

import app.animix.core.*
import app.animix.data.library.*
import app.animix.ui.screens.home.carouselItem
import app.animix.ui.screens.home.carouselStartPage
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class ProfileTest {
    @Test fun profileNameIsLimitedToThirtyFiveCharactersThroughEveryEditPath() = runTest {
        val name = "А".repeat(50)
        assertEquals(35, LocalProfile().edited(ProfileDraft(name), emptySet(), 123).name.length)
        val library = Library(DiskStore("build/test-profile-limit-${Random.nextInt(1_000_000)}"), backgroundScope).apply { load() }
        library.renameProfile(name)
        assertEquals("А".repeat(35), library.state.value.profile.name)
        library.updateProfile(ProfileDraft(name), emptySet())
        assertEquals("А".repeat(35), library.state.value.profile.name)
        val legacyDisk = DiskStore("build/test-profile-legacy-limit-${Random.nextInt(1_000_000)}")
        legacyDisk.write("library.json", AppJson.encodeToString(LibraryData.serializer(), LibraryData(profile = LocalProfile(name = name))))
        val legacy = Library(legacyDisk, backgroundScope).apply { load() }
        assertEquals(35, legacy.state.value.profile.name.length)
    }

    @Test fun reopeningTheEditorDoesNotRestoreACancelledDraft() {
        val navigator = Navigator()
        navigator.openLink("animix://edit-profile")
        val first = navigator.current!!.key
        navigator.back()
        navigator.openLink("animix://edit-profile")
        assertNotEquals(first, navigator.current!!.key)
        assertTrue(navigator.current is Route.EditProfile)
    }

    @Test fun oldProfilesLoadWithoutLosingTheirNameOrCreationDate() {
        val profile = AppJson.decodeFromString(LocalProfile.serializer(), """{"name":"Илья","createdAt":123}""")
        assertEquals("Илья", profile.name)
        assertEquals(123L, profile.createdAt)
        assertNull(profile.avatar)
        assertNull(profile.accountId)
        assertEquals("", profile.about)
    }

    @Test fun editingPreservesServerIdentityAndOnlyAllowsUnlockedBadges() {
        val original = LocalProfile(name = "Илья", createdAt = 123, accountId = "account-1", handle = "ilya")
        val media = ProfileMedia(localFileName = "avatar-1.png", assetId = "asset-1", url = "https://example.com/avatar.png")
        val changed = original.edited(ProfileDraft("  Новое   имя  ", "  О себе  ", media, badgeAchievementId = "locked"), setOf("unlocked"), 456)
        assertEquals("Новое имя", changed.name)
        assertEquals("О себе", changed.about)
        assertEquals("account-1", changed.accountId)
        assertEquals("ilya", changed.handle)
        assertEquals(123L, changed.createdAt)
        assertEquals(456L, changed.updatedAt)
        assertEquals(media, changed.avatar)
        assertNull(changed.badgeAchievementId)
        assertEquals("unlocked", changed.edited(changed.draft().copy(badgeAchievementId = "unlocked"), setOf("unlocked"), 789).badgeAchievementId)
        assertEquals("Новое имя", changed.edited(changed.draft().copy(name = " "), emptySet(), 789).name)
    }

    @Test fun appearanceAndBadgeSurviveARealRepositoryRestart() = runTest {
        val disk = DiskStore("build/test-profile-${Random.nextInt(1_000_000)}")
        val firstRun = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val library = Library(disk, firstRun).apply { load() }
        val draft = ProfileDraft("Илья", "Смотрю и сохраняю любимое", ProfileMedia(localFileName = "avatar-1.png"),
            ProfileMedia(assetId = "banner-1", url = "https://example.com/banner.png"), "first_completed")
        library.updateProfile(draft, setOf("first_completed"))
        library.flush()
        firstRun.coroutineContext[Job]!!.cancelAndJoin()
        val restored = Library(disk, backgroundScope).apply { load() }
        assertEquals(draft, restored.state.value.profile.draft())
        assertTrue(restored.state.value.profile.updatedAt > 0)
        restored.updateProfile(draft.copy(avatar = null, banner = null, badgeAchievementId = null), emptySet())
        assertNull(restored.state.value.profile.avatar)
        assertNull(restored.state.value.profile.banner)
        assertNull(restored.state.value.profile.badgeAchievementId)
        disk.clear()
    }

    @Test fun carouselWrapsForwardFromLastToFirstAndStartsOnTheFirstItem() {
        for (count in 2..10) {
            val start = carouselStartPage(count)
            assertTrue(start > count)
            assertEquals(0, carouselItem(start, count))
            assertEquals(count - 1, carouselItem(start + count - 1, count))
            assertEquals(0, carouselItem(start + count, count))
            assertEquals(count - 1, carouselItem(start - 1, count))
        }
        assertEquals(0, carouselStartPage(1))
        assertEquals(0, carouselItem(0, 1))
    }
}
