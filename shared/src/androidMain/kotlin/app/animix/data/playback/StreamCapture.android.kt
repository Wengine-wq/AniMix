package app.animix.data.playback

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.animix.core.AndroidPlatform
import app.animix.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

actual fun createStreamCapture(): StreamCapture = AndroidStreamCapture()
internal actual fun resolveMediaReference(base: String, reference: String): String = java.net.URI(base).resolve(reference).normalize().toASCIIString()

internal class CaptureBridge(private val report: (String) -> Unit) {
    @JavascriptInterface fun postMessage(message: String) {
        if (message.length > 65_536) return
        val value = runCatching {
            val data = AppJson.parseToJsonElement(message).jsonObject
            if (data["type"]?.jsonPrimitive?.content == "stream") data["value"]?.jsonPrimitive?.content else null
        }.getOrNull()
        if (value != null) report(value)
    }
}

private class AndroidStreamCapture : StreamCapture {
    @SuppressLint("SetJavaScriptEnabled")
    override fun candidates(iframeUrl: String): Flow<StreamCandidate> = callbackFlow {
        val webView = WebView(AndroidPlatform.context)
        val userAgent = webView.settings.userAgentString
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun report(value: String) {
            if (captureRank(value) >= 0 && seen.add(value)) trySend(StreamCandidate(value, userAgent))
        }
        try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
            }
            webView.addJavascriptInterface(CaptureBridge(::report), "AnimixCapture")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { view.evaluateJavascript(captureScript, null) }
                override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript(captureScript, null) }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    report(request.url.toString())
                    return null
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && httpUrlOrNull(request.url.toString())?.host != httpUrlOrNull(iframeUrl)?.host

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) close(StreamResolutionException("Не удалось открыть источник видео."))
                }
            }
            webView.layout(0, 0, 960, 540)
            webView.onResume()
            webView.loadUrl(iframeUrl, mapOf("Referer" to EmbedReferer))
        } catch (_: Exception) {
            close(StreamResolutionException("Не удалось запустить системный WebView."))
        }
        awaitClose {
            Handler(Looper.getMainLooper()).post {
                webView.stopLoading()
                webView.removeJavascriptInterface("AnimixCapture")
                webView.webViewClient = WebViewClient()
                webView.onPause()
                webView.destroy()
            }
        }
    }.flowOn(Dispatchers.Main.immediate)
}
