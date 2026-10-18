package app.animix.data.importer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun OAuthBrowser(url: String, redirectPrefix: String, onRedirect: (String) -> Unit, onCancel: () -> Unit) {
    val redirect by rememberUpdatedState(onRedirect)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    var done = false
                    fun intercept(target: String?): Boolean {
                        if (done || target == null || !target.startsWith(redirectPrefix)) return false
                        done = true
                        stopLoading()
                        redirect(target)
                        return true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = intercept(request.url.toString())
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { intercept(url) }
                    }
                    loadUrl(url)
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
        )
    }
}
