package app.animix

import app.animix.core.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.random.Random
import kotlin.test.*

class SettingsWriteFailureTest {
    @Test fun failedWriteKeepsUiChangesAndCanRetryAfterStorageIsRestored() = runBlocking {
        val root = File("build/test-preferences-write-${Random.nextInt(1_000_000_000)}").canonicalFile
        root.mkdirs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = AppSettings(DiskStore(root.path), scope)
        try {
            settings.load()
            settings.flush()
            val file = File(root, "settings.preferences_pb")
            val backup = File(root, "saved.preferences_pb")
            assertTrue(file.renameTo(backup))
            assertTrue(file.mkdir()) // A directory cannot be replaced by DataStore's atomic file write.
            settings.update { it.copy(skipEndings = true, preferredQuality = "1080p") }
            assertFailsWith<IOException> { settings.flush() }
            assertTrue(settings.state.value.skipEndings)
            assertEquals("1080p", settings.state.value.preferredQuality)
            assertTrue(file.delete()) // Only the empty directory created above.
            assertTrue(backup.renameTo(file))
            settings.flush()
            val restored = AppSettings(DiskStore(root.path), scope).apply { load() }.state.value
            assertTrue(restored.skipEndings)
            assertEquals("1080p", restored.preferredQuality)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
