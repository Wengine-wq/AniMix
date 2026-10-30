package app.animix.data.playback

import app.animix.core.Platform
import java.io.File
import kotlinx.coroutines.*

actual suspend fun createReviewMusic(bytes:ByteArray):ReviewMusic=withContext(Dispatchers.IO){
    val file=File(Platform.cacheDir,"review-horizon.ogg").apply{if(!exists())writeBytes(bytes)}
    DesktopReviewMusic(file)
}
internal class DesktopReviewMusic(private val file:File):ReviewMusic{
        private val factory=nativeMediaFactory("--vout=dummy")
        internal val player=factory.mediaPlayers().newMediaPlayer()
        private var started=false;private var closed=false
        override val playing:Boolean get()=started&&!closed&&player.status().isPlaying
        init{player.audio().setVolume(22)}
        override fun play(){if(closed)return;if(!started){started=true;player.media().play(file.toPath().toUri().toASCIIString(),":input-repeat=65535")}else player.controls().play()}
        override fun pause(){if(started&&!closed)player.controls().setPause(true)}
        override fun close(){if(!closed){closed=true;player.controls().stop();player.release();factory.release()}}
}
