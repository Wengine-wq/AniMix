package app.animix

import app.animix.core.AppLog
import app.animix.core.Platform
import java.io.File
import org.junit.Test
import kotlinx.coroutines.*
import kotlin.test.*

class DiagnosticsTest {
    @Test fun localJournalContainsSafeEventsButNoExceptionSecrets() = runBlocking {
        val secret = "private-test-token-782194"
        AppLog.failure("DiagnosticsTest", "redaction_probe", IllegalStateException("https://example.test/?token=$secret"))
        val folder = File(Platform.dataDir, "diagnostics")
        withTimeout(5_000) {
            while (folder.listFiles().orEmpty().none { it.isFile && it.readText().contains("redaction_probe") }) delay(25)
        }
        val text = folder.listFiles().orEmpty().filter { it.isFile }.joinToString { it.readText() }
        assertTrue(text.contains("IllegalStateException"))
        assertFalse(text.contains(secret))
        assertFalse(text.contains("https://example.test"))
    }
}
