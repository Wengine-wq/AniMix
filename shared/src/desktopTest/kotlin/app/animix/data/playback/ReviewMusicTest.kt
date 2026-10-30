package app.animix.data.playback

import java.io.File
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

class ReviewMusicTest{
    @Test fun nativeEngineDecodesMusicAndCanPauseAndRelease()=runBlocking<Unit>{
        val music=createReviewMusic(File("src/commonMain/composeResources/files/review_horizon.ogg").readBytes()) as DesktopReviewMusic
        try{
            music.player.audio().setVolume(0)
            music.play()
            withTimeout(10000){while((music.player.media().info().statistics()?.decodedAudio()?:0)<1)delay(100)}
            assertTrue(music.player.media().info().statistics().decodedAudio()>0)
            music.pause();delay(300);assertFalse(music.player.status().isPlaying)
            music.play();withTimeout(5000){while(!music.player.status().isPlaying)delay(50)}
        }finally{music.close();music.close()}
    }
}
