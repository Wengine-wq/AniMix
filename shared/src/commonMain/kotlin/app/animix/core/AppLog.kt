package app.animix.core

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Local diagnostics only. Callers supply fixed event names, never raw exception messages or URLs. */
object AppLog {
    private val logger by lazy {
        val platform = platformLogWriter()
        val rolling = runCatching {
            val folder = Path(Platform.dataDir, "diagnostics")
            SystemFileSystem.createDirectories(folder)
            RollingFileLogWriter(RollingFileLogWriterConfig(
                logFileName = "animix", logFilePath = folder, rollOnSize = 256L * 1024, maxLogFiles = 3,
            ))
        }.getOrNull()
        Logger(loggerConfigInit(*(listOfNotNull(platform, rolling).toTypedArray()), minSeverity = Severity.Info), "AniMix")
    }

    fun info(area: String, event: String) {
        logger.i(tag = "AniMix/$area") { event }
    }

    fun failure(area: String, event: String, error: Throwable) {
        // Throwable messages/causes can contain authorization headers, signed URLs or local paths.
        if (error is kotlinx.coroutines.CancellationException) return
        logger.w(tag = "AniMix/$area") { "$event (${error::class.simpleName ?: "Exception"})" }
    }
}
