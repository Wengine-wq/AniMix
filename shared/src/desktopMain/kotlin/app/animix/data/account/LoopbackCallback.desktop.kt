package app.animix.data.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

actual fun openLoopbackCallback(): LoopbackCallback? = DesktopLoopback()

private class DesktopLoopback : LoopbackCallback {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    override val returnUri = "http://127.0.0.1:${server.localPort}/oauth/callback"

    override suspend fun await(): String = withContext(Dispatchers.IO) {
        while (true) {
            ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                it.soTimeout = 5000
                val line = it.getInputStream().bufferedReader(Charsets.UTF_8).readLine().orEmpty()
                val path = line.split(' ').getOrNull(1).orEmpty()
                val ours = line.startsWith("GET ") && path.startsWith("/oauth/callback?")
                val failed = "error=" in path
                val (status, title, text) = when {
                    !ours -> Triple("404 Not Found", "AniMix", "Здесь ничего нет.")
                    failed -> Triple("200 OK", "Вход не завершён", "Вернитесь в AniMix и попробуйте ещё раз.")
                    else -> Triple("200 OK", "Вход выполнен", "Можно закрыть вкладку и вернуться в AniMix.")
                }
                val body = page(title, text).toByteArray(Charsets.UTF_8)
                it.getOutputStream().apply {
                    write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    write(body)
                    flush()
                }
                if (ours) return@withContext "http://127.0.0.1:${server.localPort}$path"
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    override fun close() { runCatching { server.close() } }

    private fun page(title: String, text: String) = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title — AniMix</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#08080F;color:#F4F1F6;
font-family:Segoe UI,Inter,system-ui,sans-serif}main{text-align:center;padding:24px}h1{font-size:28px;margin:0 0 12px;color:#FFB4C8}
p{margin:0;color:#B9B3BE;font-size:16px}</style></head><body><main><h1>$title</h1><p>$text</p></main></body></html>"""
}
