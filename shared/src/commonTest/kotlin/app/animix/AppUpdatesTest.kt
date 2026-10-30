package app.animix

import app.animix.core.AppJson
import app.animix.data.update.*
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

class AppUpdatesTest {
    private fun releases(text: String) = AppJson.parseToJsonElement(text) as JsonArray
    private fun asset(name: String) = """{"name":"$name","browser_download_url":"https://github.com/Wengine-wq/AniMix/releases/download/x/$name","size":10}"""
    private val list = releases("""[
        {"tag_name":"v2.5.33","name":"AniMix 2.5.33","draft":false,"prerelease":false,"assets":[${asset("AniMix-2.5.33-universal.apk")}]},
        {"tag_name":"v1.0.7-re","name":"Pre","draft":false,"prerelease":true,"assets":[${asset("a-universal.apk")}]},
        {"tag_name":"v1.0.6-re","name":"AniMix 1.0.6 re","body":"## Что нового\n- Быстрее каталог","html_url":"https://github.com/Wengine-wq/AniMix/releases/tag/v1.0.6-re","draft":false,"prerelease":false,
         "assets":[${asset("AniMix-1.0.6-arm64-v8a.apk")},${asset("AniMix-1.0.6-universal.apk")},${asset("AniMix-1.0.6.msi")},${asset("AniMix-1.0.6-Windows.zip")}]},
        {"tag_name":"v1.0.4-re","name":"old","draft":false,"prerelease":false,"assets":[${asset("old-universal.apk")}]}
    ]""")

    @Test fun versionsCompareByNumbers() {
        assertTrue(compareVersions("1.0.10", "1.0.9") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertTrue(compareVersions("1.0.5", "2.5.33") < 0)
    }

    @Test fun onlyNewerPublishedReleasesOfThisLineAreOffered() {
        val update = assertNotNull(pickUpdate(list, "1.0.5", "Android", listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("1.0.6", update.version)
        assertEquals("1.0.6 re", update.label)
        assertEquals("AniMix-1.0.6-arm64-v8a.apk", update.asset.name)
        assertEquals("AniMix-1.0.6-universal.apk", pickUpdate(list, "1.0.5", "Android", listOf("x86"))?.asset?.name)
        assertEquals("AniMix-1.0.6.msi", pickUpdate(list, "1.0.5", "Windows")?.asset?.name)
        // 2.x tags never count, and the same or a newer build sees nothing.
        assertNull(pickUpdate(list, "1.0.6", "Android", listOf("arm64-v8a")))
        assertNull(pickUpdate(releases("""[{"tag_name":"v9.9.9","assets":[${asset("x-universal.apk")}]}]"""), "1.0.5", "Android"))
    }

    @Test fun downloadsComeOnlyFromGitHub() {
        val foreign = releases("""[{"tag_name":"v1.0.9-re","assets":[{"name":"a-universal.apk","browser_download_url":"https://evil.example/a.apk","size":1}]}]""")
        assertNull(pickUpdate(foreign, "1.0.5", "Android"))
    }
}
