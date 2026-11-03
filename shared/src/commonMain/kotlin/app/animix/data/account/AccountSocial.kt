package app.animix.data.account

import app.animix.core.*
import app.animix.model.Anime
import app.animix.data.playback.NativePlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import io.ktor.http.encodeURLParameter

/** [online] and [lastSeenAt] (ms) come with the friends list from servers that send presence; older ones leave them null. */
@Serializable data class SocialUser(val id:String, @SerialName("display_name") val name:String,
    @SerialName("avatar_url") val avatar:String?=null, val status:String="none",
    val online:Boolean?=null, @SerialName("last_seen_at") val lastSeenAt:Long?=null)
@Serializable data class HorizonPlayback(val anime:Anime, val episode:String, val dubbingKey:String, val quality:String,
    val positionMs:Long, val playing:Boolean=true)
@Serializable data class HorizonDevice(@SerialName("device_id") val id:String,val label:String,val platform:String,val active:Boolean,
    @SerialName("seen_at") val seenAt:Long,val playback:HorizonPlayback?=null) {
    fun online(now:Long=TrustedClock.now()) = active && now-seenAt in -5000..90000
}
@Serializable data class HorizonHandoff(val id:String,@SerialName("source_id") val source:String,@SerialName("target_id") val target:String,
    val playback:HorizonPlayback,@SerialName("claimed_at") val claimedAt:Long?=null)
data class SocialState(val friends:List<SocialUser> = emptyList(),val devices:List<HorizonDevice> = emptyList(),
    val incoming:List<HorizonHandoff> = emptyList(),val error:String?=null,val busy:Boolean=false)

/** Who sees one part of the profile: "all", "friends" or "nobody". Name and avatar are always public. */
@Serializable data class ProfileVisibility(val collection:String="friends",val stats:String="friends",
    val achievements:String="friends",val presence:String="friends")

/** Presence and handoff use the same authenticated account; no CDN links or credentials are sent. */
class AccountSocial(private val account:AniMixAccount,private val store:DiskStore,private val scope:CoroutineScope,
    private val playerDispatcher:CoroutineDispatcher=Dispatchers.Main.immediate) {
    private val mutable=MutableStateFlow(SocialState()); val state=mutable.asStateFlow()
    private var device=""; private var active=true; private var bound:Pair<NativePlayer,()->HorizonPlayback>?=null
    private val activeState=MutableStateFlow(true);val foreground=activeState.asStateFlow()
    private var receiving:HorizonHandoff?=null; private var outgoing:String?=null
    private var sourcePlayer:NativePlayer?=null
    /** Screens that show friends or devices right now; while any is open (or a player is bound) updates come every 10 s, else every 30 s. */
    private val attention=MutableStateFlow(0)
    /** Servers before 1.2 answer the combined request with 404: then the four separate ones are used. */
    private var combined=true
    private var label=""
    val deviceId get()=device
    suspend fun start(automatic:Boolean=true) {
        device=checkNotNull(store.readChecked("sync-device.txt"))
        if(!automatic)return
        scope.launch { account.state.map { it.user?.id }.distinctUntilChanged().collectLatest { id ->
            mutable.value=SocialState(); outgoing=null; receiving=null;sourcePlayer=null
            if(id==null) return@collectLatest
            var tick=0
            while(isActive) {
                val fast=bound!=null || attention.value>0
                // In the background of the app: the same presence every 30 s, friends every minute and a half.
                if(active && (fast || tick%3==0)) try { pulse(id,withFriends=fast || tick%9==0) }
                catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){mutable.value=mutable.value.copy(error="Не удалось обновить онлайн-данные. Повторим при подключении.")}
                tick++
                // Wakes early when a screen starts watching, so an opened list is fresh at once.
                withTimeoutOrNull(10000){ attention.first{ it>0 && !fast } }
            }
        } }
    }
    fun setActive(value:Boolean) {
        active=value
        activeState.value=value
        val id=account.state.value.user?.id ?: return
        if(device.isNotEmpty()) scope.launch { try{heartbeat(id)}catch(cancel:CancellationException){throw cancel}catch(_:Exception){} }
    }
    private fun owner()=account.state.value.user?.id ?: throw AccountException("Войдите в аккаунт AniMix.")
    /** A screen showing friends or devices keeps them fresh while it is open; call the returned function on leave. */
    fun watch():()->Unit { attention.update{it+1}; var released=false; return { if(!released){released=true;attention.update{it-1}} } }
    private suspend fun presence():JsonObject {
        if(label.isEmpty()) label=withContext(Dispatchers.Default){ runCatching{ deviceName() }.getOrDefault(Platform.name) }.take(64)
        val playback=bound?.let { (player,create)->if(player.state.value.ready && !player.state.value.ended) create() else null }
        return buildJsonObject {
            put("device",device);put("label",label);put("platform",Platform.name);put("active",active)
            if(playback!=null)put("playback",AppJson.encodeToJsonElement(playback))
        }
    }
    /** Presence, own devices, handoffs and (when asked) friends in one request. */
    private suspend fun pulse(id:String,withFriends:Boolean) {
        if(combined) {
            val (status,body)=account.postJson("/v1/me/pulse",JsonObject(presence()+("friends" to JsonPrimitive(withFriends))),id)
            if(status==404 || status==405) combined=false
            else {
                if(account.state.value.user?.id!=id) return
                val devices=body["devices"]?.let{ AppJson.decodeFromJsonElement<List<HorizonDevice>>(it) } ?: mutable.value.devices
                val friends=body["users"]?.let{ AppJson.decodeFromJsonElement<List<SocialUser>>(it) } ?: mutable.value.friends
                applyHandoffs(AppJson.decodeFromJsonElement(body["handoffs"] ?: JsonArray(emptyList())))
                mutable.value=mutable.value.copy(devices=devices,friends=friends,error=null)
                return
            }
        }
        heartbeat(id); refresh(); pollHandoffs(id)
    }
    suspend fun refresh() {
        val id=owner()
        val friends=account.getJson("/v1/friends",id)["users"] ?: JsonArray(emptyList())
        val devices=account.getJson("/v1/me/devices",id)["devices"] ?: JsonArray(emptyList())
        if(account.state.value.user?.id==id) mutable.value=mutable.value.copy(friends=AppJson.decodeFromJsonElement(friends),devices=AppJson.decodeFromJsonElement(devices),error=null)
    }
    suspend fun search(query:String):List<SocialUser> {
        if(query.trim().length<2)return emptyList()
        return AppJson.decodeFromJsonElement(account.getJson("/v1/users/search?q=${query.trim().encodeURLParameter()}",owner())["users"] ?: JsonArray(emptyList()))
    }
    suspend fun friendAction(id:String,remove:Boolean=false) { account.command("/v1/friends/$id",owner(),delete=remove); refresh() }
    private val overviews=mutableMapOf<String,Pair<Long,JsonObject>>()
    /** A friend's page and its collection and awards pages share one answer for half a minute. */
    suspend fun overview(id:String,fresh:Boolean=false):JsonObject {
        val now=TrustedClock.now()
        if(!fresh) overviews[id]?.takeIf{ now-it.first in 0..30_000 }?.let{ return it.second }
        return account.getJson("/v1/users/$id/overview?v=2",owner()).also{ overviews[id]=now to it }
    }
    /** Per-part visibility; a server before 1.2 knows only one switch for the collection and statistics. */
    suspend fun visibility():ProfileVisibility {
        val raw=account.getJson("/v1/me/privacy",owner())
        raw["visibility"]?.let { return AppJson.decodeFromJsonElement(it) }
        val shared=if(raw["library_visible"]?.jsonPrimitive?.booleanOrNull==false) "nobody" else "friends"
        return ProfileVisibility(shared,shared,shared,"friends")
    }
    suspend fun setVisibility(value:ProfileVisibility):ProfileVisibility {
        val (_,raw)=account.putJson("/v1/me/privacy",buildJsonObject{put("visibility",AppJson.encodeToJsonElement(value));put("library_visible",value.collection!="nobody")}.toString(),owner())
        return raw["visibility"]?.let { AppJson.decodeFromJsonElement<ProfileVisibility>(it) } ?: value
    }
    /** Signs another device out of the account: its session ends on the server, its presence disappears. */
    suspend fun removeDevice(id:String) {
        require(id!=device){"This device signs out from «Аккаунт»"}
        val raw=account.command("/v1/me/devices/${id.encodeURLParameter()}",owner(),delete=true)
        raw["devices"]?.let { mutable.value=mutable.value.copy(devices=AppJson.decodeFromJsonElement(it)) }
    }
    private suspend fun heartbeat(id:String) { account.putJson("/v1/me/presence",presence().toString(),id) }
    fun bind(player:NativePlayer,anime:Anime,episode:String,dubbing:String,quality:()->String):()->Unit {
        bound=player to { HorizonPlayback(anime,episode,dubbing,quality(),player.state.value.positionMs,player.state.value.playing) }
        val pending=receiving?.takeIf { it.playback.anime.key==anime.key && it.playback.episode==episode && it.playback.dubbingKey==dubbing }
        val receivingAccount=account.state.value.user?.id
        val receivingJob=if(pending!=null) scope.launch(playerDispatcher) {
            try {
                withTimeout(45000){player.state.first{it.ready}}
                check(receivingAccount!=null && account.state.value.user?.id==receivingAccount){"Account changed"}
                player.seekTo(pending.playback.positionMs)
                if(!pending.playback.playing)player.pause()
                val claimed=account.command("/v1/me/handoff/${pending.id}/claim",receivingAccount,buildJsonObject{put("device",device)})
                if(claimed["claimed"]?.jsonPrimitive?.booleanOrNull!=true) mutable.value=mutable.value.copy(error="Передача устарела. Исходное устройство не остановлено.")
                receiving=null
            }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){mutable.value=mutable.value.copy(error="Не удалось подтвердить приём. Исходное устройство не остановлено.")}
        } else null
        return {receivingJob?.cancel();if(bound?.first===player)bound=null;if(sourcePlayer===player)sourcePlayer=null}
    }
    suspend fun send(target:String) {
        val binding=bound ?: throw AccountException("Сначала откройте серию в плеере.")
        val snapshot=binding.second()
        val hex=(1..32).map{"0123456789abcdef"[kotlin.random.Random.nextInt(16)]}.joinToString("")
        val requestId="${hex.take(8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}"
        val result=account.command("/v1/me/handoff",owner(),buildJsonObject {
            put("device",device);put("target",target);put("request_id",requestId);put("playback",AppJson.encodeToJsonElement(snapshot))
        })
        outgoing=result["id"]?.jsonPrimitive?.content;sourcePlayer=binding.first
        if(outgoing==null)throw AccountException("Сервер не подтвердил передачу.")
    }
    fun receive(item:HorizonHandoff) {receiving=item;mutable.value=mutable.value.copy(incoming=mutable.value.incoming.filterNot{it.id==item.id})}
    internal suspend fun refreshHandoffs()=pollHandoffs(owner())
    private suspend fun pollHandoffs(id:String) {
        applyHandoffs(AppJson.decodeFromJsonElement(account.getJson("/v1/me/handoff?device=$device",id)["handoffs"] ?: JsonArray(emptyList())))
    }
    private suspend fun applyHandoffs(rows:List<HorizonHandoff>) {
        withContext(playerDispatcher) {
            if(rows.any{it.id==outgoing && it.claimedAt!=null}) {sourcePlayer?.pause();sourcePlayer=null;outgoing=null}
        }
        mutable.value=mutable.value.copy(incoming=rows.filter{it.target==device && it.claimedAt==null && it.id!=receiving?.id})
    }
}
