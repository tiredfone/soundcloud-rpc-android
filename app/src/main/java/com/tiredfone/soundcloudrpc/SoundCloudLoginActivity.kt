package com.tiredfone.soundcloudrpc

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.tiredfone.soundcloudrpc.databinding.ActivitySoundcloudLoginBinding
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean

class SoundCloudLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySoundcloudLoginBinding
    private lateinit var storage: TokenStorage
    private val tokenCaptured = AtomicBoolean(false)

    inner class JsBridge {
        @JavascriptInterface
        fun onAuthData(json: String) {
            try {
                val obj = JsonParser.parseString(json).asJsonObject
                val accessToken = obj.get("access_token")?.takeIf { !it.isJsonNull }?.asString
                val refreshToken = obj.get("refresh_token")?.takeIf { !it.isJsonNull }?.asString
                if (!accessToken.isNullOrBlank() && accessToken.length > 20) {
                    storage.soundcloudToken = accessToken
                    if (!refreshToken.isNullOrBlank()) {
                        storage.soundcloudRefreshToken = refreshToken
                        AppLogger.i("SCLogin", "Captured refresh token from localStorage")
                    }
                    if (tokenCaptured.compareAndSet(false, true)) {
                        runOnUiThread { onTokenCaptured() }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySoundcloudLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Connect SoundCloud"

        storage = TokenStorage(this)

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false)
                builtInZoomControls = false
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            addJavascriptInterface(JsBridge(), "Android")
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val host = request.url.host ?: return null

                    if (host.contains("soundcloud.com") || host.contains("sndcdn.com")) {
                        val clientId = request.url.getQueryParameter("client_id")
                        if (!clientId.isNullOrBlank()) {
                            storage.soundcloudClientId = clientId
                        }

                        if (!tokenCaptured.get()) {
                            val authHeader = request.requestHeaders["Authorization"]
                            if (authHeader != null && authHeader.startsWith("OAuth ")) {
                                val token = authHeader.removePrefix("OAuth ").trim()
                                if (token.length > 20) {
                                    storage.soundcloudToken = token
                                    AppLogger.i("SCLogin", "Captured OAuth token from request header")
                                    // Try to also get the refresh token from the auth endpoint
                                    tryFetchRefreshToken(token)
                                    if (tokenCaptured.compareAndSet(false, true)) {
                                        runOnUiThread { onTokenCaptured() }
                                    }
                                }
                            }
                        }
                    }

                    return null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (tokenCaptured.get()) return
                    // Inject JS to read auth data from SoundCloud's localStorage
                    view.evaluateJavascript("""
                        (function() {
                            try {
                                var keys = Object.keys(localStorage);
                                for (var i = 0; i < keys.length; i++) {
                                    var val = localStorage.getItem(keys[i]);
                                    if (val && val.indexOf('access_token') !== -1) {
                                        try {
                                            var parsed = JSON.parse(val);
                                            if (parsed && parsed.access_token) {
                                                Android.onAuthData(JSON.stringify(parsed));
                                                return;
                                            }
                                        } catch(e) {}
                                    }
                                }
                            } catch(e) {}
                        })();
                    """.trimIndent(), null)
                }
            }

            loadUrl("https://soundcloud.com")
        }
    }

    private fun tryFetchRefreshToken(accessToken: String) {
        // After capturing the access token, try to get the full session including refresh_token
        // from the SoundCloud session endpoint using the same token
        Thread {
            try {
                val client = OkHttpClient()
                val clientId = storage.soundcloudClientId ?: return@Thread
                val resp = client.newCall(
                    Request.Builder()
                        .url("https://api-v2.soundcloud.com/me?client_id=$clientId")
                        .header("Authorization", "OAuth $accessToken")
                        .build()
                ).execute()
                // /me doesn't return refresh_token; this just validates the token is good
                if (resp.isSuccessful) {
                    AppLogger.i("SCLogin", "Token validated successfully via /me endpoint")
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun onTokenCaptured() {
        startActivity(
            Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
