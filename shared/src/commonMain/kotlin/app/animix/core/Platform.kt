package app.animix.core

import io.ktor.client.engine.HttpClientEngine

/** Facts about the device the app runs on. */
expect object Platform {
    /** Short platform name used in the User-Agent, e.g. "Android" or "Windows". */
    val name: String

    /** Directory for durable app data (settings, library). */
    val dataDir: String

    /** Directory for data that can be thrown away (HTTP and image caches). */
    val cacheDir: String
}

expect fun createHttpEngine(): HttpClientEngine

/**
 * What this device is called in the account's device list: the phone's maker and model, or the PC's
 * motherboard, with the system version — «Samsung Galaxy S23 · Android 14», «ASUS PRIME B550M-A · Windows 11».
 * Blocking on desktop the first time; call off the main thread.
 */
expect fun deviceName(): String
