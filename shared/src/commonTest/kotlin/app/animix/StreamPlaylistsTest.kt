package app.animix

import app.animix.data.playback.StreamPlaylists
import app.animix.data.playback.captureRank
import app.animix.data.playback.httpUrlOrNull
import app.animix.data.playback.isMediaUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamPlaylistsTest {
    @Test fun playerJsPreservesSignedQueriesAndCommaValues() {
        val sources = StreamPlaylists.parseExpression("""[1080p]https:\/\/cdn.test/1080.mp4?sig=a,b&amp;expires=42,[720]//cdn.test/720.mp4?sig=c""")
        assertEquals(mapOf("1080p" to "https://cdn.test/1080.mp4?sig=a,b&expires=42", "720p" to "https://cdn.test/720.mp4?sig=c"), sources)
    }

    @Test fun masterResolvesRelativePathsAndChoosesHigherBandwidthForSameHeight() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720,CODECS="avc1,mp4a"
            ../720/low.m3u8?token=child
            #EXT-X-STREAM-INF:BANDWIDTH=2000,RESOLUTION=1280x720
            ../720/high.m3u8?token=child
            #EXT-X-STREAM-INF:BANDWIDTH=3000,RESOLUTION=1920x1080
            /1080/index.m3u8?token=root
        """.trimIndent()
        val variants = StreamPlaylists.master(body, "https://cdn.test/hls/master.m3u8?token=parent")
        assertEquals("https://cdn.test/720/high.m3u8?token=child", variants["720p"])
        assertEquals("https://cdn.test/1080/index.m3u8?token=root", variants["1080p"])
    }

    @Test fun siblingChangesOnlyResolutionPathNotSignedQuery() {
        val siblings = StreamPlaylists.siblings("https://cdn.test/720.mp4:hls:manifest.m3u8?signature=720&redirect=/720.mp4:hls:")
        assertEquals("https://cdn.test/1080.mp4:hls:manifest.m3u8?signature=720&redirect=/720.mp4:hls:", siblings["1080p"])
        assertTrue(StreamPlaylists.siblings("https://cdn.test/master.m3u8?height=720").isEmpty())
    }

    @Test fun mediaValidationUsesPathNotTokensOrQueryExtensions() {
        assertTrue(isMediaUrl("https://cdn.test/episode.mp4?sig=adfox-bet-720"))
        assertFalse(isMediaUrl("https://cdn.test/ads/preroll.mp4"))
        assertFalse(isMediaUrl("https://cdn.test/page?next=master.m3u8"))
        assertFalse(isMediaUrl("blob:https://cdn.test/episode.mp4"))
        assertNull(httpUrlOrNull("file:///tmp/master.m3u8"))
        assertNull(httpUrlOrNull("https://user:password@cdn.test/episode.mp4"))
    }

    @Test fun rankingPrefersExpressionThenMasterThenMediaThenMp4() {
        assertTrue(captureRank("[720p]https://cdn.test/720.mp4,[480p]https://cdn.test/480.mp4") > captureRank("https://cdn.test/master.m3u8"))
        assertTrue(captureRank("https://cdn.test/master.m3u8") > captureRank("https://cdn.test/index.m3u8"))
        assertTrue(captureRank("https://cdn.test/index.m3u8") > captureRank("https://cdn.test/episode.mp4"))
        assertEquals(-1, captureRank("https://cdn.test/ads/preroll.mp4"))
    }

    @Test fun autoAndHighestQualityComeFirst() {
        assertEquals(listOf("Авто", "1080p", "720p", "360p"), StreamPlaylists.sorted(mapOf("360p" to "a", "720p" to "b", "Авто" to "c", "1080p" to "d")).keys.toList())
    }
}
