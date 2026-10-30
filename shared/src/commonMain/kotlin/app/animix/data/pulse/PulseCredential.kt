package app.animix.data.pulse

/** Supplied at runtime on the device. Never a build constant, resource or tracked file. */
expect suspend fun pulseApiKey(): String?
