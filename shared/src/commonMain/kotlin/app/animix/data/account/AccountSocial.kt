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

/** Presence and handoff use the same authenticated account; no CDN links or credentials are sent. */
class AccountSocial(private val account:AniMixAccount,private val store:DiskStore,private val scope:CoroutineScope,
    private val playerDispatcher:CoroutineDispatcher=Dispatchers.Main.immediate) {
    private val mutable=MutableStateFlow(SocialState()); val state=mutable.asStateFlow()
    private var device=""; private var active=true; private var bound:Pair<NativePlayer,()->HorizonPlayback>?=null
    private val activeState=MutableStateFlow(true);val foreground=activeState.asStateFlow()
    private var receiving:HorizonHandoff?=null; private var outgoing:String?=null
    private var sourcePlayer:NativePlayer?=null
    val deviceId get()=device
    suspend fun start(automatic:Boolean=true) {
        device=checkNotNull(store.readChecked("sync-device.txt"))
        if(!automatic)return
        scope.launch { account.state.map { it.user?.id }.distinctUntilChanged().collectLatest { id ->
            mutable.value=SocialState(); outgoing=null; receiving=null;sourcePlayer=null
            if(id==null) return@collectLatest
            while(isActive) {
                if(active) try { heartbeat(id); refresh(); pollHandoffs(id) }
                catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){mutable.value=mutable.value.copy(error="Не удалось обновить онлайн-данные. Повторим при подключении.")}
                delay(10000)
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
    suspend fun overview(id:String)=account.getJson("/v1/users/$id/overview",owner())
    suspend fun privacy(visible:Boolean) { account.putJson("/v1/me/privacy",buildJsonObject{put("library_visible",visible)}.toString(),owner()) }
    suspend fun privacy():Boolean=account.getJson("/v1/me/privacy",owner())["library_visible"]?.jsonPrimitive?.booleanOrNull ?: true
    private suspend fun heartbeat(id:String) {
        val playback=bound?.let { (player,create)->if(player.state.value.ready && !player.state.value.ended) create() else null }
        account.putJson("/v1/me/presence",buildJsonObject {
            put("device",device);put("label","${Platform.name} · ${device.takeLast(4)}");put("platform",Platform.name);put("active",active)
            if(playback!=null)put("playback",AppJson.encodeToJsonElement(playback))
        }.toString(),id)
    }
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
        val rows=AppJson.decodeFromJsonElement<List<HorizonHandoff>>(account.getJson("/v1/me/handoff?device=$device",id)["handoffs"] ?: JsonArray(emptyList()))
        withContext(playerDispatcher) {
            if(rows.any{it.id==outgoing && it.claimedAt!=null}) {sourcePlayer?.pause();sourcePlayer=null;outgoing=null}
        }
        mutable.value=mutable.value.copy(incoming=rows.filter{it.target==device && it.claimedAt==null && it.id!=receiving?.id})
    }
}
