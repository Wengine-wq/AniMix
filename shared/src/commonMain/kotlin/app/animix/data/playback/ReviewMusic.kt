package app.animix.data.playback

interface ReviewMusic { val playing:Boolean;fun play();fun pause();fun close() }
expect suspend fun createReviewMusic(bytes:ByteArray):ReviewMusic
