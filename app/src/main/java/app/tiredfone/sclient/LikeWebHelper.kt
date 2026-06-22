package app.tiredfone.sclient

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LikeWebHelper private constructor(context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile private var INSTANCE: LikeWebHelper? = null

        fun get(context: Context) = INSTANCE ?: synchronized(this) {
            INSTANCE ?: LikeWebHelper(context.applicationContext).also { INSTANCE = it }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var ready = false
    private val pending = mutableListOf<() -> Unit>()
    private var resultCallback: ((Boolean) -> Unit)? = null

    init {
        main.post {
            webView = WebView(context).apply {
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(JsInterface(), "SClientLike")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView, request: WebResourceRequest
                    ): WebResourceResponse? = null  // let all requests through

                    override fun onPageFinished(view: WebView, url: String) {
                        if (!ready) {
                            ready = true
                            pending.forEach { it() }
                            pending.clear()
                        }
                    }
                }
                loadUrl("https://soundcloud.com/")
            }
        }
    }

    suspend fun like(trackId: Long, token: String, clientId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            execute("PUT", trackId, token, clientId) { cont.resume(it) }
        }

    suspend fun unlike(trackId: Long, token: String, clientId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            execute("DELETE", trackId, token, clientId) { cont.resume(it) }
        }

    private fun execute(method: String, trackId: Long, token: String, clientId: String, callback: (Boolean) -> Unit) {
        val t = token.replace("\\", "\\\\").replace("'", "\\'")
        val c = clientId.replace("\\", "\\\\").replace("'", "\\'")
        val body = if (method == "PUT") "body: '{}'," else ""
        val js = """
            (function() {
                fetch('https://api-v2.soundcloud.com/me/track_likes/$trackId?client_id=$c', {
                    method: '$method',
                    headers: {
                        'Authorization': 'OAuth $t',
                        'Content-Type': 'application/json',
                        'Origin': 'https://soundcloud.com'
                    },
                    $body
                    credentials: 'include'
                }).then(function(r) {
                    window.SClientLike.onResult(r.ok);
                }).catch(function(e) {
                    window.SClientLike.onResult(false);
                });
            })();
        """.trimIndent()

        val op: () -> Unit = {
            resultCallback = callback
            webView?.evaluateJavascript(js, null)
        }
        main.post { if (ready) op() else pending.add(op) }
    }

    inner class JsInterface {
        @JavascriptInterface
        fun onResult(ok: Boolean) {
            main.post {
                resultCallback?.invoke(ok)
                resultCallback = null
            }
        }
    }
}
