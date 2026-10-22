package app.animix

import app.animix.core.*
import app.animix.data.library.*
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class TextCharactersTest {
    @Test fun limitsPreserveCompleteEmojiCombiningMarksFlagsAndJoinedFamilies() {
        for (character in listOf("А", "😀", "е\u0301", "🇷🇺", "👩🏽‍💻", "👨‍👩‍👧‍👦")) {
            val name = character.repeat(36)
            assertEquals(36, name.characterCount())
            assertEquals(character.repeat(35), name.takeCharacters(35))
            assertEquals(character.repeat(35), LocalProfile().edited(ProfileDraft(name), emptySet(), 1).name)
        }
        assertEquals("", "😀".takeCharacters(0))
        assertEquals("😀", "😀".takeCharacters(35))
    }

    @Test fun emojiNameSurvivesLoadingEditingSavingAndReloading() = runTest {
        val disk = DiskStore("build/test-unicode-${Random.nextInt(1_000_000_000)}")
        val name = "😀".repeat(35)
        disk.writeChecked("library.json", AppJson.encodeToString(LibraryData.serializer(), LibraryData(profile = LocalProfile(name))))
        val library = Library(disk, backgroundScope).apply { load() }
        assertEquals(name, library.state.value.profile.name)
        library.renameProfile(name + "X")
        library.flush()
        assertEquals(name, Library(disk, backgroundScope).apply { load() }.state.value.profile.name)
        disk.clear()
    }
}
