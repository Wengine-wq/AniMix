package app.animix.data.account

import app.animix.core.*
import app.animix.data.achievements.Achievements
import app.animix.data.library.*
import app.animix.model.Anime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.random.Random

data class AccountSyncState(val syncing: Boolean = false, val pending: Boolean = true, val lastSuccessAt: Long? = null, val error: String? = null)

@Serializable private data class SyncJournal(
    val base: SyncPayload? = null,
    val ownedUsage: SyncUsage = SyncUsage(),
    val sampledUsage: SyncUsage = SyncUsage(),
    val uploadedMedia: Map<String, String> = emptyMap(),
    val pendingApply: PendingApply? = null,
    /** The server revision [base] was read at; an unchanged revision is answered without the payload. */
    val revision: Long = 0,
)
@Serializable private data class PendingApply(val before: LibraryData, val after: LibraryData, val preferences: JsonObject, val receipt: String)
@Serializable private data class CachedAccount(val library: LibraryData, val achievements: app.animix.data.achievements.AchievementState,
    val preferences: JsonObject = JsonObject(emptyMap()))

/** Existing account + library remain authoritative locally. This adapter adds durable reconciliation. */
class AccountSync(
    private val account: AniMixAccount,
    private val library: Library,
    private val achievements: Achievements,
    private val settings: AppSettings,
    private val store: DiskStore,
    private val scope: CoroutineScope,
    private val titles: suspend (List<Int>) -> List<Anime> = { ids -> ids.map { Anime.placeholder(it) } },
    private val mediaBytes: suspend (String) -> ByteArray = { file -> withContext(Dispatchers.IO) {
        require(file.matches(Regex("[A-Za-z0-9_-]+\\.png")))
        val path = Path(Platform.dataDir, "profile-media", file)
        require((SystemFileSystem.metadataOrNull(path)?.size ?: 0) in 1..4_194_304)
        SystemFileSystem.source(path).buffered().use { it.readByteArray() }
    } },
) {
    private val mutable = MutableStateFlow(AccountSyncState())
    val state = mutable.asStateFlow()
    private val gate = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private var owner: String? = null
    private var device = ""
    private var journal = SyncJournal()

    @OptIn(FlowPreview::class)
    suspend fun start(automatic: Boolean = true) {
        if (started) return
        owner = store.readChecked("sync-owner.txt")
        device = store.readChecked("sync-device.txt") ?: "device-${nowMillis()}-${Random.nextLong().toULong().toString(16)}".also {
            store.writeChecked("sync-device.txt", it)
        }
        started = true
        if (!automatic) return
        scope.launch {
            combine(library.state, achievements.syncState, settings.state) { _, _, _ -> Unit }
                .onEach { mutable.value = mutable.value.copy(pending = pendingChanges()) }
                .debounce(1500).collect { wake.trySend(Unit) }
        }
        scope.launch {
            account.state.map { it.user?.id }.distinctUntilChanged().collectLatest { id ->
                mutable.value = AccountSyncState()
                if (id == null) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    synchronize()
                    withTimeoutOrNull(30_000) { wake.receive() }
                    delay(1000) // coalesce edits and avoid an offline hot retry loop
                }
            }
        }
    }

    fun requestSync() { wake.trySend(Unit) }

    /** Also public for an explicit retry; never reports success before disk and server both saved. */
    suspend fun synchronize() = gate.withLock {
        val user = account.state.value.user ?: return@withLock
        mutable.value = mutable.value.copy(syncing = true, error = null)
        try {
            attach(user.id)
            checkAccount(user.id)
            applyPending(user.id)
            achievements.flush()
            val known = journal.revision.takeIf { journal.base != null && it > 0 }
            val raw = account.getJson("/v1/me/sync" + (known?.let { "?known=$it" } ?: ""), user.id)
            val unchanged = known != null && raw["unchanged"]?.jsonPrimitive?.booleanOrNull == true
            var remote = if (unchanged) SyncEnvelope(known, journal.base) else readEnvelope(raw)
            // Nothing new on either side: a few bytes from the server and no upload.
            if (unchanged && !pendingChanges()) {
                mutable.value = AccountSyncState(pending = false, lastSuccessAt = TrustedClock.now())
                return@withLock
            }
            if (remote.payload == null && journal.base == null) bootstrap(user)
            uploadImages(user.id)
            val sampled = library.state.value
            val currentUsage = SyncUsage(sampled.watchDays, sampled.usage)
            journal = journal.copy(ownedUsage = usageDelta(currentUsage, journal.sampledUsage, journal.ownedUsage), sampledUsage = currentUsage)
            saveJournal(user.id) // before the network: repeats reuse the same device counters
            val preferences = JsonObject(SyncJson.encodeToJsonElement(settings.state.value).jsonObject.filterKeys { it in SyncedPreferenceKeys })
            val local = SyncPayload(library = sampled.copy(watchRemainderMs = 0, syncReceipt = null), achievements = achievements.syncState.value,
                preferences = preferences, usage = mapOf(device to journal.ownedUsage))
            val base = journal.base ?: SyncPayload()
            var accepted: SyncPayload? = null
            repeat(4) {
                if (accepted != null) return@repeat
                checkAccount(user.id)
                val cloud = remote.payload ?: SyncPayload()
                val counters = (cloud.usage.keys + local.usage.keys).associateWith { key ->
                    usageUnion(cloud.usage[key] ?: SyncUsage(), local.usage[key] ?: SyncUsage())
                }
                val usage = aggregateUsage(counters.values)
                val mergedLibrary = mergeLibrary(base.library, local.library, cloud.library).copy(
                    watchDays = usage.days, usage = usage.years, watchRemainderMs = 0,
                )
                val mergedAwards = mergeAwards(local.achievements, cloud.achievements)
                val merged = SyncPayload(library = mergedLibrary, achievements = mergedAwards,
                    preferences = (mergeJson(base.preferences, preferences, cloud.preferences) as? JsonObject) ?: preferences, usage = counters,
                    summary = publicSummary(mergedLibrary, mergedAwards))
                val upload = merged.copy(library = merged.library.copy(profile = merged.library.profile.copy(accountId = user.id,
                    avatar = merged.library.profile.avatar?.copy(localFileName = null), banner = merged.library.profile.banner?.copy(localFileName = null))))
                if (upload == remote.payload) { accepted = upload; return@repeat }
                val (status, body) = account.putJson("/v1/me/sync", SyncJson.encodeToString(SyncEnvelope(remote.revision, upload)), user.id)
                remote = readEnvelope(body)
                if (status == 200) accepted = remote.payload ?: throw AccountException("Сервер не вернул сохранённые данные.")
            }
            val saved = accepted ?: throw AccountException("Другое устройство обновляет профиль. Повторим синхронизацию.")
            checkAccount(user.id)
            if (saved == journal.base && !pendingChanges()) {
                if (journal.revision != remote.revision) { journal = journal.copy(revision = remote.revision); saveJournal(user.id) }
                mutable.value = AccountSyncState(pending = false, lastSuccessAt = TrustedClock.now())
                return@withLock
            }
            val current = library.state.value
            val after = run {
                val merged = mergeLibrary(sampled, current, saved.library)
                val inFlight = usageDelta(SyncUsage(current.watchDays, current.usage), SyncUsage(sampled.watchDays, sampled.usage), SyncUsage())
                val usage = aggregateUsage(listOf(SyncUsage(saved.library.watchDays, saved.library.usage), inFlight))
                fun media(remote: ProfileMedia?, local: ProfileMedia?): ProfileMedia? = if (remote?.url != null && remote.url == local?.url) remote.copy(localFileName = local.localFileName) else remote
                merged.copy(watchDays = usage.days, usage = usage.years,
                    profile = merged.profile.copy(avatar = media(merged.profile.avatar, current.profile.avatar), banner = media(merged.profile.banner, current.profile.banner)))
            }
            // Durable intent + library receipt make a crash before/after applying the response
            // distinguishable. Remote time cannot become a new local playback delta on restart.
            journal = journal.copy(base = saved, revision = remote.revision,
                ownedUsage = usageDelta(SyncUsage(current.watchDays, current.usage), currentUsage, journal.ownedUsage),
                sampledUsage = SyncUsage(after.watchDays, after.usage),
                pendingApply = PendingApply(current, after, preferences, "$device-${Random.nextLong().toULong().toString(16)}"))
            saveJournal(user.id)
            applyPending(user.id)
            mutable.value = AccountSyncState(pending = pendingChanges(), lastSuccessAt = TrustedClock.now())
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            AppLog.failure("AccountSync", "sync_failed", error)
            mutable.value = mutable.value.copy(error = (error as? AccountException)?.message
                ?: "Нет синхронизации. Изменения сохранены на устройстве; повторим при подключении.")
        } finally { mutable.value = mutable.value.copy(syncing = false) }
    }

    private suspend fun attach(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        if (owner != id) {
            achievements.flush()
            val current = library.state.value
            val currentAwards = achievements.syncState.value
            // What the previous account last saved in the cloud. Anything this device did after it,
            // as a guest once signed out, belongs to the person signing in now.
            val left = owner?.let { previous -> store.readChecked("sync-journal-$previous.json")?.let { SyncJson.decodeFromString<SyncJournal>(it) }?.base }
            owner?.let { previous ->
                store.writeChecked("sync-cache-$previous.json", SyncJson.encodeToString(CachedAccount(current, currentAwards,
                    JsonObject(SyncJson.encodeToJsonElement(settings.state.value).jsonObject.filterKeys { it in SyncedPreferenceKeys }))))
            }
            val cache = store.readChecked("sync-cache-$id.json")?.let { SyncJson.decodeFromString<CachedAccount>(it) }
            if (owner != null || cache != null) {
                val target = cache?.library ?: LibraryData()
                // Without a saved state of the previous account nothing can be told apart: nothing moves.
                val guestBase = if (owner == null) LibraryData() else left?.library ?: current
                val guestAwards = if (owner == null) currentAwards.unlocked
                    else left?.let { base -> currentAwards.unlocked.filterKeys { it !in base.achievements.unlocked } }.orEmpty()
                library.applySynced { live -> carryGuestProgress(guestBase, live, target) }
                achievements.applySynced(mergeAwards(cache?.achievements ?: app.animix.data.achievements.AchievementState(),
                    app.animix.data.achievements.AchievementState(unlocked = guestAwards)), replace = true)
                settings.update { current ->
                    val defaults = SyncJson.encodeToJsonElement(Settings()).jsonObject.filterKeys { it in SyncedPreferenceKeys }
                    SyncJson.decodeFromJsonElement(JsonObject(SyncJson.encodeToJsonElement(current).jsonObject + defaults + cache?.preferences.orEmpty()))
                }
                settings.flush()
                library.flush()
            }
            journal = store.readChecked("sync-journal-$id.json")?.let { SyncJson.decodeFromString<SyncJournal>(it) } ?: SyncJournal()
            owner = id
            store.writeChecked("sync-owner.txt", id)
        } else {
            journal = store.readChecked("sync-journal-$id.json")?.let { SyncJson.decodeFromString<SyncJournal>(it) } ?: SyncJournal()
        }
    }

    private suspend fun bootstrap(user: AccountUser) {
        // The existing server's lists/awards are brought over as history, never fake playback.
        val old = account.getJson("/v1/library", user.id)["entries"] as? JsonArray ?: JsonArray(emptyList())
        val records = old.mapNotNull { it as? JsonObject }.associateBy { it["shikimori_id"]?.jsonPrimitive?.intOrNull }
        val metadata = titles(records.keys.filterNotNull()).associateBy { it.id }
        checkAccount(user.id)
        library.importTitles(records.mapNotNull { (id, value) -> metadata[id]?.takeIf { it.title.isNotBlank() }?.let { anime ->
            ImportedTitle(anime, ListStatus.entries.find { it.apiValue == value["status"]?.jsonPrimitive?.content },
                value["episodes_watched"]?.jsonPrimitive?.intOrNull ?: 0, value["score"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 })
        } })
        val profile = account.getJson("/v1/me", user.id)["user"] as? JsonObject
        if (profile != null) library.applySynced { current ->
            if (current.profile.updatedAt != 0L) current else current.copy(profile = current.profile.copy(
                name = profile["display_name"]?.jsonPrimitive?.contentOrNull ?: user.name,
                about = profile["about"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                avatar = profile["avatar_url"]?.jsonPrimitive?.contentOrNull?.let { ProfileMedia(url = it) },
                banner = profile["banner_url"]?.jsonPrimitive?.contentOrNull?.let { ProfileMedia(url = it) },
                badgeAchievementId = profile["badge_achievement_id"]?.jsonPrimitive?.contentOrNull))
        }
        val oldAwards = account.getJson("/v1/me/achievements", user.id)["achievements"] as? JsonObject
        if (oldAwards != null) achievements.applySynced(app.animix.data.achievements.AchievementState(
            unlocked = oldAwards.mapNotNull { (key, value) -> value.jsonPrimitive.longOrNull?.let { key to it } }.toMap()))
    }

    private suspend fun uploadImages(id: String) {
        for (kind in listOf("avatar", "banner")) {
            val original = if (kind == "avatar") library.state.value.profile.avatar else library.state.value.profile.banner
            val file = original?.localFileName ?: continue
            if (original.url != null && journal.uploadedMedia[file] == original.url) continue
            val url = account.uploadMedia(kind, mediaBytes(file), id)
            checkAccount(id)
            library.applySynced { current ->
                val profile = current.profile
                val live = if (kind == "avatar") profile.avatar else profile.banner
                val uploaded = original.copy(assetId = url.substringAfter("/media/", url), url = url)
                if (live != original) current else current.copy(profile = if (kind == "avatar") profile.copy(avatar = uploaded)
                    else profile.copy(banner = uploaded))
            }
            library.flush()
            journal = journal.copy(uploadedMedia = journal.uploadedMedia + (file to url))
            saveJournal(id)
        }
    }

    private suspend fun applyPending(id: String) {
        val pending = journal.pendingApply ?: return
        checkAccount(id)
        library.applySynced { current ->
            if (current.syncReceipt == pending.receipt) current else {
                val merged = mergeLibrary(pending.before, current, pending.after)
                val extra = usageDelta(SyncUsage(current.watchDays, current.usage), SyncUsage(pending.before.watchDays, pending.before.usage), SyncUsage())
                val usage = aggregateUsage(listOf(SyncUsage(pending.after.watchDays, pending.after.usage), extra))
                merged.copy(watchDays = usage.days, usage = usage.years, syncReceipt = pending.receipt)
            }
        }
        library.flush()
        achievements.applySynced(checkNotNull(journal.base).achievements)
        settings.update { current ->
            val snapshot = SyncJson.encodeToJsonElement(current).jsonObject
            val live = JsonObject(snapshot.filterKeys { it in SyncedPreferenceKeys })
            val merged = mergeJson(pending.preferences, live, checkNotNull(journal.base).preferences) as JsonObject
            SyncJson.decodeFromJsonElement(JsonObject(snapshot + merged))
        }
        settings.flush()
        journal = journal.copy(pendingApply = null)
        saveJournal(id)
    }

    private fun checkAccount(id: String) { if (account.state.value.user?.id != id) throw CancellationException("Account changed") }
    private fun pendingChanges(): Boolean {
        val base = journal.base ?: return true
        val data = library.state.value
        val clean = data.copy(watchRemainderMs = 0, syncReceipt = null, profile = data.profile.copy(avatar = data.profile.avatar?.copy(localFileName = null), banner = data.profile.banner?.copy(localFileName = null)))
        val preferences = JsonObject(SyncJson.encodeToJsonElement(settings.state.value).jsonObject.filterKeys { it in SyncedPreferenceKeys })
        return clean != base.library || achievements.syncState.value != base.achievements || preferences != base.preferences
    }
    private fun readEnvelope(value: JsonObject): SyncEnvelope = SyncJson.decodeFromJsonElement<SyncEnvelope>(value).also {
        require(it.revision >= 0 && (it.payload == null || it.payload.version == 1)) { "Unsupported sync version" }
    }
    private suspend fun saveJournal(id: String) = store.writeChecked("sync-journal-$id.json", SyncJson.encodeToString(journal))
}
