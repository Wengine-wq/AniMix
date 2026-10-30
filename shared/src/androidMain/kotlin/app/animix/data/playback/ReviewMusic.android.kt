package app.animix.data.playback

import android.media.MediaPlayer
import app.animix.core.AndroidPlatform
import java.io.File
import kotlinx.coroutines.*

actual suspend fun createReviewMusic(bytes:ByteArray):ReviewMusic {
    val file=withContext(Dispatchers.IO){File(AndroidPlatform.context.cacheDir,"review-horizon.ogg").apply{if(!exists())writeBytes(bytes)}}
    return withContext(Dispatchers.Main.immediate){object:ReviewMusic{
        private var ready=false;private var wanted=false;private var released=false
        private val engine=MediaPlayer().apply{
            setDataSource(file.absolutePath);isLooping=true;setVolume(.22f,.22f)
            setOnPreparedListener{ready=true;if(wanted&&!released)start()};prepareAsync()
        }
        override val playing:Boolean get()=ready&&!released&&engine.isPlaying
        override fun play(){wanted=true;if(ready&&!released)engine.start()}
        override fun pause(){wanted=false;if(ready&&!released&&engine.isPlaying)engine.pause()}
        override fun close(){if(!released){released=true;engine.release()}}
    }}
}
