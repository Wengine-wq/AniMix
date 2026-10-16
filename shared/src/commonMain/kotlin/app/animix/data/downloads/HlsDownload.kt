package app.animix.data.downloads

import app.animix.data.playback.ResolvedStream
import app.animix.data.playback.httpUrlOrNull
import app.animix.data.playback.resolveMediaReference
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

internal class SourceExpired : Exception()
internal data class HlsResource(val url: String, val filename: String)
internal data class HlsVariant(val url: String, val height: Int, val bandwidth: Long, val audioGroup: String?)
internal data class HlsPlan(val manifests: Map<String, String>, val resources: List<HlsResource>, val durationMs: Long)

internal object HlsManifest {
    private val attributes = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    fun attributes(line: String): Map<String, String> = attributes.findAll(line).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
    fun variants(body: String, base: String): List<HlsVariant> {
        val lines = body.lines().map { it.trim() }
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapIndexedNotNull null
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return@mapIndexedNotNull null
            val attrs = attributes(line)
            HlsVariant(resolveMediaReference(base, next), attrs["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0, attrs["BANDWIDTH"]?.toLongOrNull() ?: 0, attrs["AUDIO"])
        }
    }
    fun choose(variants: List<HlsVariant>, quality: String): HlsVariant {
        val target = Regex("[0-9]+").find(quality)?.value?.toIntOrNull()
        return if (target == null) variants.maxWith(compareBy<HlsVariant> { it.height }.thenBy { it.bandwidth })
        else variants.filter { it.height in 1..target }.maxWithOrNull(compareBy<HlsVariant> { it.height }.thenBy { it.bandwidth })
            ?: variants.minWith(compareBy<HlsVariant> { it.height }.thenByDescending { it.bandwidth })
    }
    fun rewriteMedia(body: String, base: String, resources: MutableList<HlsResource>): Pair<String, Long> {
        require(body.trimStart().startsWith("#EXTM3U")) { "Источник вернул не HLS-плейлист." }
        require(body.contains("#EXT-X-ENDLIST")) { "Скачивание незавершённой прямой трансляции не поддерживается." }
        fun resource(reference: String): String {
            val url = resolveMediaReference(base, reference)
            require(httpUrlOrNull(url) != null) { "Неподдерживаемый адрес части потока." }
            resources.firstOrNull { it.url == url }?.let { return it.filename }
            val extension = httpUrlOrNull(url)!!.encodedPath.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,5}")) } ?: "bin"
            val file = "resource-${resources.size.toString().padStart(6, '0')}.$extension"
            resources += HlsResource(url, file)
            return file
        }
        var duration = 0L
        val result = body.lineSequence().map { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> { duration += ((line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0) * 1000).toLong(); line }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = attributes(line)
                    require(attrs["METHOD"] in setOf("NONE", "AES-128") && (attrs["KEYFORMAT"] == null || attrs["KEYFORMAT"] == "identity")) { "Этот формат шифрования не поддерживается для офлайн-загрузки." }
                    val uri = attrs["URI"]
                    if (uri == null) line else line.replace(Regex("""URI="[^"]*""""), "URI=\"${resource(uri)}\"")
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = attributes(line)["URI"] ?: error("В потоке отсутствует адрес инициализации.")
                    line.replace(Regex("""URI="[^"]*""""), "URI=\"${resource(uri)}\"")
                }
                line.isNotEmpty() && !line.startsWith('#') -> resource(line)
                else -> line
            }
        }.joinToString("\n")
        require(resources.isNotEmpty()) { "В плейлисте нет частей видео." }
        return result to duration
    }
}

/** Persist each resource independently; Range resumes a partially transferred resource. */
class HlsDownload(private val http: HttpClient, private val files: OfflineFiles, private val assembler: MediaAssembler) {
    private suspend fun manifest(url: String, headers: Map<String, String>): Pair<String, String> =
        http.prepareGet(url) { expectSuccess = false; headers { headers.forEach { (k, v) -> set(k, v) }; set(HttpHeaders.Accept, "*/*") } }.execute { response ->
            if (response.status.value in setOf(401, 403)) throw SourceExpired()
            check(response.status.value in 200..299) { "Не удалось получить плейлист (${response.status.value})." }
            val body = response.bodyAsChannel().readBuffer(1_048_577L).readString()
            check(body.length <= 1_048_576 && body.trimStart().startsWith("#EXTM3U")) { "Источник вернул некорректный плейлист." }
            body to response.call.request.url.toString()
        }

    private suspend fun plan(url: String, quality: String, headers: Map<String, String>): HlsPlan {
        var (body, base) = manifest(url, headers)
        var selected: HlsVariant? = null
        var audioUri: String? = null
        repeat(3) {
            val variants = HlsManifest.variants(body, base)
            if (variants.isNotEmpty()) {
                selected = HlsManifest.choose(variants, quality)
                val group = selected.audioGroup
                if (group != null) audioUri = body.lineSequence().filter { it.startsWith("#EXT-X-MEDIA:") }.map { HlsManifest.attributes(it) }
                    .filter { it["TYPE"] == "AUDIO" && it["GROUP-ID"] == group && it["URI"] != null }
                    .sortedByDescending { it["DEFAULT"] == "YES" }.firstOrNull()?.get("URI")?.let { resolveMediaReference(base, it) }
                val next = manifest(selected.url, headers); body = next.first; base = next.second
            }
        }
        check(HlsManifest.variants(body, base).isEmpty()) { "Слишком много вложенных плейлистов." }
        val resources = mutableListOf<HlsResource>()
        val video = HlsManifest.rewriteMedia(body, base, resources)
        val manifests = linkedMapOf("video.m3u8" to video.first)
        if (audioUri != null) {
            val audio = manifest(audioUri, headers)
            manifests["audio.m3u8"] = HlsManifest.rewriteMedia(audio.first, audio.second, resources).first
            manifests["index.m3u8"] = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"offline\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=${selected?.bandwidth ?: 1000000},AUDIO=\"offline\"\nvideo.m3u8\n"
        } else manifests["index.m3u8"] = video.first
        return HlsPlan(manifests, resources, video.second)
    }

    suspend fun download(stream: ResolvedStream, id: String, quality: String, progress: (Int, Int, Long, Boolean) -> Unit): String = withContext(Dispatchers.IO) {
        val targetHeight = Regex("[0-9]+").find(quality)?.value?.toIntOrNull()
        val renditions = stream.qualities.entries.mapNotNull { entry -> Regex("[0-9]+").find(entry.key)?.value?.toIntOrNull()?.let { it to entry.value } }
        val fallback = if (targetHeight == null) renditions.maxByOrNull { it.first } else
            renditions.filter { it.first <= targetHeight }.maxByOrNull { it.first } ?: renditions.minByOrNull { it.first }
        // A master may declare external audio. Opening its video-only variant would lose the dubbing.
        val url = stream.qualities["Авто"] ?: stream.qualities[quality] ?: fallback?.second ?: stream.qualities.values.first()
        files.mkdir("work/$id")
        val output = "$id.mp4"
        if (httpUrlOrNull(url)?.encodedPath?.lowercase()?.endsWith(".mp4") == true) {
            transfer(url, stream.headers, "work/$id/video.mp4") { bytes -> progress(0, 1, bytes, false) }
            files.move("work/$id/video.mp4", output)
            files.deleteTree("work/$id")
            progress(1, 1, files.size(output), false)
            return@withContext output
        }
        val plan = plan(url, quality, stream.headers)
        val fingerprint = plan.manifests.values.joinToString("\n") + "\n" + plan.resources.joinToString("\n") { httpUrlOrNull(it.url)?.let { url -> "${url.host}${url.encodedPath}" } ?: it.url }
        val oldFingerprint = files.read("work/$id/plan.txt")
        if (oldFingerprint != null && oldFingerprint != fingerprint) { files.deleteTree("work/$id"); files.mkdir("work/$id") }
        files.write("work/$id/plan.txt", fingerprint)
        plan.manifests.forEach { (name, content) -> files.write("work/$id/$name", content) }
        var downloaded = 0L
        plan.resources.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val path = "work/$id/${item.filename}"
            transfer(item.url, stream.headers, path) { bytes -> progress(index, plan.resources.size, downloaded + bytes, false) }
            downloaded += files.size(path)
            progress(index + 1, plan.resources.size, downloaded, false)
        }
        val assembling = "$id.assembling.mp4"
        files.delete(assembling)
        assembler.assemble(files.absolute("work/$id/index.m3u8"), files.absolute(assembling), plan.durationMs) {
            progress((it * 1000).toInt(), 1000, downloaded, true)
        }
        check(files.size(assembling) > 0) { "Собранный файл пуст." }
        files.move(assembling, output)
        files.deleteTree("work/$id")
        output
    }

    private suspend fun transfer(url: String, requestHeaders: Map<String, String>, target: String, progress: (Long) -> Unit) {
        if (files.exists(target)) { progress(files.size(target)); return }
        val part = "$target.part"
        var offset = files.size(part)
        val etag = files.read("$target.etag")
        http.prepareGet(url) {
            expectSuccess = false
            headers {
                requestHeaders.forEach { (k, v) -> set(k, v) }
                set(HttpHeaders.Accept, "*/*")
                if (offset > 0) { set(HttpHeaders.Range, "bytes=$offset-"); if (etag != null) set(HttpHeaders.IfRange, etag) }
            }
        }.execute { response ->
            if (response.status.value in setOf(401, 403)) throw SourceExpired()
            val contentRange = response.headers[HttpHeaders.ContentRange]
            if (response.status.value == 416 && contentRange?.substringAfterLast('/')?.toLongOrNull() == offset && offset > 0) {
                files.move(part, target); files.delete("$target.etag"); progress(offset); return@execute
            }
            check(response.status.value in setOf(200, 206)) { "Не удалось скачать часть видео (${response.status.value})." }
            if (response.status.value == 206) {
                check(contentRange?.startsWith("bytes $offset-") == true) { "Сервер вернул неверный диапазон данных." }
                val nextTag = response.headers[HttpHeaders.ETag]
                check(etag == null || nextTag == null || etag == nextTag) { "Видео изменилось на сервере. Удалите загрузку и добавьте заново." }
            } else offset = 0 // server ignored Range/If-Range: replace rather than append duplicate bytes
            response.headers[HttpHeaders.ETag]?.let { files.write("$target.etag", it) }
            val channel = response.bodyAsChannel()
            val expected = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { offset + it }
            SystemFileSystem.sink(files.path(part), append = offset > 0).buffered().use { sink ->
                var count = offset
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val block = channel.readBuffer(65536L)
                    if (block.size == 0L) break
                    count += block.size
                    sink.write(block, block.size)
                    progress(count)
                }
                check(expected == null || expected == count) { "Источник прервал передачу части видео." }
                check(count > 0) { "Источник вернул пустой файл." }
            }
            files.move(part, target)
            files.delete("$target.etag")
        }
    }
}
