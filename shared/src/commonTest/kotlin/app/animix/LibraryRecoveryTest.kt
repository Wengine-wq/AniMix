package app.animix

import app.animix.core.AppJson
import app.animix.core.DiskStore
import app.animix.data.library.*
import app.animix.model.Anime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class LibraryRecoveryTest {
    private val anime = Anime.placeholder(52991).copy(title = "Фрирен", episodes = 28)
    private fun disk() = DiskStore("build/test-library-recovery-${Random.nextInt(1_000_000_000)}")

    @Test fun oneIncompatibleEntryDoesNotEraseTheOtherEntriesOrTheProfileEvenOnFlush() = runTest {
        val disk = disk()
        val original = """{"profile":{"name":"Илья"},"entries":{"s52991":{"anime":${AppJson.encodeToString(Anime.serializer(), anime)},"favorite":true},"broken":{"anime":{"id":1}}}}"""
        disk.writeChecked("library.json", original)
        val library = Library(disk, backgroundScope)
        assertFailsWith<IllegalStateException> { library.load() }
        assertFailsWith<IllegalStateException> { library.flush() }
        assertEquals(original, disk.readChecked("library.json"))
        // Restoring the file and retrying works on the same instance.
        val healthy = LibraryData(LocalProfile(name = "Илья"), mapOf(anime.key to LibraryEntry(anime, favorite = true)))
        disk.writeChecked("library.json", AppJson.encodeToString(LibraryData.serializer(), healthy))
        library.load()
        assertEquals(healthy, library.state.value)
        disk.clear()
    }

    @Test fun validBackupRestoresLibraryAndArchivesTheIncompatibleOriginal() = runTest {
        val disk = disk()
        val healthy = LibraryData(LocalProfile(name = "Илья"), mapOf(anime.key to LibraryEntry(anime, status = ListStatus.Watching, favorite = true)))
        val backup = AppJson.encodeToString(LibraryData.serializer(), healthy)
        disk.writeChecked("library.json", "{broken")
        disk.writeChecked("library.backup.json", backup)
        val library = Library(disk, backgroundScope)
        library.load()
        library.flush()
        assertEquals(healthy, library.state.value)
        assertEquals(healthy, AppJson.decodeFromString(LibraryData.serializer(), disk.readChecked("library.json")!!))
        assertEquals(backup, disk.readChecked("library.backup.json"))
        disk.clear()
    }

    @Test fun savingChangesKeepsThePreviousValidSnapshotAndUnchangedFlushDoesNotRotateIt() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        library.setStatus(anime, ListStatus.Planned)
        library.flush()
        val planned = disk.readChecked("library.json")
        library.toggleFavorite(anime)
        library.flush()
        library.flush()
        assertEquals(planned, disk.readChecked("library.backup.json"))
        disk.clear()
    }

    @Test fun unresolvedTitlesCannotBeAddedAndLoadedMetadataRepairsExistingEntries() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val unresolved = Anime.placeholder(anime.id)
        library.setStatus(unresolved, ListStatus.Planned)
        library.toggleFavorite(unresolved)
        assertTrue(library.state.value.entries.isEmpty())
        library.refreshMetadata(anime)
        assertTrue(library.state.value.entries.isEmpty())
        // Finish the initial asynchronous save before replacing this file with a legacy fixture.
        library.flush()
        disk.clear()

        val legacy = LibraryData(entries = mapOf(unresolved.key to LibraryEntry(unresolved, status = ListStatus.Planned, favorite = true, updatedAt = 123)))
        disk.writeChecked("library.json", AppJson.encodeToString(LibraryData.serializer(), legacy))
        val reloaded = Library(disk, backgroundScope).apply { load() }
        reloaded.refreshMetadata(anime)
        reloaded.flush()
        val entry = Library(disk, backgroundScope).apply { load() }.state.value.entry(anime)!!
        assertEquals(anime, entry.anime)
        assertEquals(ListStatus.Planned, entry.status)
        assertTrue(entry.favorite)
        assertEquals(123L, entry.updatedAt)
        disk.clear()
    }
}
