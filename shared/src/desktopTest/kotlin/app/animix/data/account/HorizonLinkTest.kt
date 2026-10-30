package app.animix.data.account

import app.animix.core.*
import app.animix.model.Anime
import app.animix.data.playback.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class HorizonLinkTest {
    private class Player:NativePlayer{
        val mutable=MutableStateFlow(PlayerSnapshot(playing=true,ready=true,positionMs=42000,durationMs=100000))
        override val state=mutable.asStateFlow();var pauses=0;var seek=0L
        override fun load(url:String,headers:Map<String,String>,startPositionMs:Long,playWhenReady:Boolean){}
        override fun play(){};override fun pause(){assertEquals("horizon-player-test",Thread.currentThread().name.substringBefore(" @coroutine"));pauses++;mutable.value=mutable.value.copy(playing=false)}
        override fun seekTo(positionMs:Long){assertEquals("horizon-player-test",Thread.currentThread().name.substringBefore(" @coroutine"));seek=positionMs};override fun setVolume(volume:Float){};override fun release(){}
    }
    @Test fun originalPausesOnlyAfterTheChosenTargetIsReadyAndAcknowledges()=runBlocking<Unit>{
        val root=Files.createTempDirectory("horizon-").toFile();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val playerDispatcher=Executors.newSingleThreadExecutor{Thread(it,"horizon-player-test")}.asCoroutineDispatcher()
        val anime=Anime.placeholder(21).copy(title="Test")
        var pending:HorizonHandoff?=null;var claims=0
        val http=HttpClient(MockEngine{request->
            val json=headersOf(HttpHeaders.ContentType,"application/json")
            when{
                request.url.encodedPath.endsWith("/claim")->{claims++;pending=pending!!.copy(claimedAt=TrustedClock.now());respond("""{"claimed":true}""",headers=json)}
                request.method==HttpMethod.Post->{
                    val body=(request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    assertFalse("iframe" in body);val payload=AppJson.parseToJsonElement(body).jsonObject
                    pending=HorizonHandoff(payload.getValue("request_id").jsonPrimitive.content,"phone","pc",AppJson.decodeFromJsonElement(payload.getValue("playback")))
                    respond(buildJsonObject{put("id",pending!!.id)}.toString(),headers=json)
                }
                else->respond(buildJsonObject{put("handoffs",AppJson.encodeToJsonElement(listOfNotNull(pending)))}.toString(),headers=json)
            }
        })
        suspend fun social(id:String):AccountSocial{
            val disk=DiskStore(java.io.File(root,id).absolutePath)
            disk.writeChecked("sync-device.txt",id);disk.writeChecked("animix-account.json","""{"accessToken":"token","refreshToken":"refresh","user":{"id":"u1","name":"Test"}}""")
            val account=AniMixAccount(http,disk,scope,"https://test").apply{load()}
            return AccountSocial(account,disk,scope,playerDispatcher).apply{start(false)}
        }
        try{
            val source=social("phone");val target=social("pc");val original=Player()
            val unbind=source.bind(original,anime,"1","kodik|studio"){"1080p"}
            source.send("pc");source.refreshHandoffs();assertEquals(0,original.pauses)
            val destination=Player().apply{mutable.value=mutable.value.copy(ready=false)}
            target.receive(pending!!);val detach=target.bind(destination,anime,"1","kodik|studio"){"1080p"}
            delay(100);assertEquals(0,claims);source.refreshHandoffs();assertEquals(0,original.pauses)
            destination.mutable.value=destination.mutable.value.copy(ready=true)
            withTimeout(5000){while(claims==0)delay(10)}
            assertEquals(42000,destination.seek)
            source.refreshHandoffs();assertEquals(1,original.pauses)
            source.refreshHandoffs();assertEquals(1,original.pauses,"Repeated acknowledgement cannot act on another player")
            detach();unbind()
            val released=Player();val release=source.bind(released,anime,"2","kodik|studio"){"1080p"}
            source.send("pc");release();pending=pending!!.copy(claimedAt=TrustedClock.now())
            source.refreshHandoffs();assertEquals(0,released.pauses,"Late acknowledgement cannot touch a released engine")
        }finally{scope.coroutineContext[Job]!!.cancelAndJoin();playerDispatcher.close();http.close();root.deleteRecursively()}
    }
}
