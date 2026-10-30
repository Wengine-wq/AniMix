package app.animix.android

import app.animix.resources.Res
import app.animix.data.playback.createReviewMusic
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewMusicTest{
    @Test fun bundledInstrumentalPlaysPausesAndReleases()=runBlocking<Unit>{
        val music=createReviewMusic(Res.readBytes("files/review_horizon.ogg"))
        try{
            withContext(Dispatchers.Main){music.play()}
            withTimeout(10000){while(!withContext(Dispatchers.Main){music.playing})delay(50)}
            withContext(Dispatchers.Main){music.pause();assertFalse(music.playing);music.play()}
            withTimeout(5000){while(!withContext(Dispatchers.Main){music.playing})delay(50)}
        }finally{withContext(Dispatchers.Main){music.close();music.close();assertFalse(music.playing)}}
    }
}
