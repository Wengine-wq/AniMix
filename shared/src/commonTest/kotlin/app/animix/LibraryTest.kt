package app.animix

import app.animix.core.DiskStore
import app.animix.data.library.Library
import app.animix.data.library.ListStatus
import app.animix.model.Anime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    private val anime = Anime.placeholder(52991).copy(title = "Фрирен", episodes = 28)

    @Test
    fun listsAndFavoritesSurviveReload() = runTest {
        val store = DiskStore("build/test-library-${Random.nextInt(1_000_000)}")
        val library = Library(store, backgroundScope)
        library.load()
        library.setStatus(anime, ListStatus.Watching)
        library.toggleFavorite(anime)
        library.flush()

        val reloaded = Library(store, backgroundScope).apply { load() }
        val entry = reloaded.state.value.entry(anime)
        assertEquals(ListStatus.Watching, entry?.status)
        assertTrue(entry?.favorite == true)
        store.clear()
    }

    @Test
    fun watchingAllEpisodesCompletesAndClearingRemovesEntry() = runTest {
        val library = Library(DiskStore("build/test-library-${Random.nextInt(1_000_000)}"), backgroundScope)
        library.load()
        library.setEpisodesWatched(anime, 3)
        assertEquals(ListStatus.Watching, library.state.value.entry(anime)?.status)
        library.setEpisodesWatched(anime, 28)
        assertEquals(ListStatus.Completed, library.state.value.entry(anime)?.status)

        library.setEpisodesWatched(anime, 0)
        library.setStatus(anime, null)
        assertNull(library.state.value.entry(anime))
    }
}
