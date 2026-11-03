package app.animix.android

import android.app.Application
import app.animix.AppGraph
import app.animix.core.AndroidPlatform

class AniMixApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.init(this)
        graph = AppGraph()
        EpisodeWorker.schedule(this)
    }
}
