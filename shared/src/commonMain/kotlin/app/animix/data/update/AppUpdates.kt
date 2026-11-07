package app.animix.data.update

import app.animix.core.AppJson
import app.animix.core.AppLog
import app.animix.core.Platform
import app.animix.core.TrustedClock
import app.animix.data.AppRelease
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Where AniMix looks for new versions. Change [REPOSITORY] to move releases to another GitHub repository. */
object UpdateSource {
    const val REPOSITORY = "Wengine-wq/AniMix"

    /**
     * Only releases of this line count, tagged like `v1.0.6-re`. Other tags in the same repository
     * (the 2.x line, `v2.5.33`) are never offered as an update.
     */
    val Tag = Regex("""^v?(\d+(?:\.\d+){1,3})[-_ ]?re$""", RegexOption.IGNORE_CASE)
}

data class UpdateAsset(val name: String, val url: String, val size: Long)

/** [behind]: how many releases of this line are newer than the installed one (this one included). */
data class AvailableUpdate(val version: String, val title: String, val notes: String, val pageUrl: String, val asset: UpdateAsset,
    val behind: Int = 1) {
    val label: String get() = "$version re"
}

/** Negative when [a] is older than [b]; missing parts count as zero ("1.0" == "1.0.0"). */
fun compareVersions(a: String, b: String): Int {
    val left = a.split('.').map { it.toIntOrNull() ?: 0 }
    val right = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

/**
 * The newest published (not draft, not pre-release) release of this line that is newer than
 * [current] and has a file for this platform: an APK for this device's ABI (or universal) on
 * Android; an installer (.msi, .exe) or else a .zip on Windows.
 */
fun pickUpdate(releases: JsonArray, current: String, platform: String, abis: List<String> = emptyList()): AvailableUpdate? {
    val newer = releases.mapNotNull { it as? JsonObject }
        .filter { (it["draft"] as? JsonPrimitive)?.booleanOrNull != true && (it["prerelease"] as? JsonPrimitive)?.booleanOrNull != true }
        .mapNotNull { release ->
            val tag = release.text("tag_name") ?: return@mapNotNull null
            val version = UpdateSource.Tag.matchEntire(tag.trim())?.groupValues?.get(1) ?: return@mapNotNull null
            if (compareVersions(version, current) <= 0) return@mapNotNull null
            val assets = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { asset ->
                val name = asset.text("name") ?: return@mapNotNull null
                val url = asset.text("browser_download_url")?.takeIf { it.startsWith("https://github.com/") } ?: return@mapNotNull null
                UpdateAsset(name, url, (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0)
            }
            val file = chooseAsset(assets, platform, abis) ?: return@mapNotNull null
            AvailableUpdate(version, release.text("name") ?: tag, release.text("body").orEmpty().take(2000),
                release.text("html_url") ?: "https://github.com/${UpdateSource.REPOSITORY}/releases", file)
        }
    return newer.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }?.copy(behind = newer.size)
}

internal fun chooseAsset(assets: List<UpdateAsset>, platform: String, abis: List<String>): UpdateAsset? = when (platform) {
    "Android" -> {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.name.contains(abi, ignoreCase = true) } }
            ?: apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apks.singleOrNull()
    }
    "Windows" -> listOf(".msi", ".exe", ".zip").firstNotNullOfOrNull { ext ->
        assets.firstOrNull { it.name.endsWith(ext, ignoreCase = true) && !it.name.contains("ios", ignoreCase = true) }
    }
    else -> null
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/** Device ABIs, most preferred first (empty on desktop). */
expect fun deviceAbis(): List<String>

/**
 * Downloads [asset] and hands it to the system: the package installer on Android, the installer
 * (or the archive in Explorer) on Windows. Returns a message for the viewer when something is
 * left for them to do, such as allowing installs from AniMix.
 */
expect suspend fun downloadAndInstall(asset: UpdateAsset, onProgress: (Float) -> Unit): String?

data class UpdateState(
    val available: AvailableUpdate? = null,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val message: String? = null,
)

/** Checks GitHub Releases at most every six hours; failures are quiet, the next launch tries again. */
class AppUpdates(private val http: HttpClient, private val scope: CoroutineScope, private val current: String = AppRelease.build) {
    private val mutable = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutable.asStateFlow()
    private var checkedAt = 0L
    private val busy = Mutex()

    fun check(force: Boolean = false) {
        val now = TrustedClock.now()
        if (!force && checkedAt != 0L && now - checkedAt < 6 * 3_600_000L) return
        if (!busy.tryLock()) return
        checkedAt = now
        scope.launch {
            try {
                val text = http.get("https://api.github.com/repos/${UpdateSource.REPOSITORY}/releases?per_page=20") {
                    header("Accept", "application/vnd.github+json")
                }.bodyAsText()
                val releases = AppJson.parseToJsonElement(text) as? JsonArray ?: JsonArray(emptyList())
                val found = pickUpdate(releases, current, Platform.name, deviceAbis())
                mutable.update { it.copy(available = found) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { AppLog.failure("Update", "check", error) }
            finally { busy.unlock() }
        }
    }

    fun install() {
        val update = mutable.value.available ?: return
        if (mutable.value.downloading) return
        mutable.update { it.copy(downloading = true, progress = 0f, message = null) }
        scope.launch {
            val message = try { downloadAndInstall(update.asset) { p -> mutable.update { it.copy(progress = p.coerceIn(0f, 1f)) } } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                AppLog.failure("Update", "install", error)
                "Не удалось скачать обновление. Проверьте сеть и попробуйте ещё раз."
            }
            mutable.update { it.copy(downloading = false, message = message) }
        }
    }
}
