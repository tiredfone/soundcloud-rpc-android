package com.tiredfone.soundcloudrpc

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.tiredfone.soundcloudrpc.databinding.ActivitySoundcloudLoginBinding
import java.util.concurrent.atomic.AtomicBoolean

class SoundCloudLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySoundcloudLoginBinding
    private lateinit var storage: TokenStorage
    private val tokenCaptured = AtomicBoolean(false)

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

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val host = request.url.host ?: return null

                    if (host.contains("soundcloud.com") || host.contains("sndcdn.com")) {
                        // Capture client_id from query params
                        val clientId = request.url.getQueryParameter("client_id")
                        if (!clientId.isNullOrBlank()) {
                            storage.soundcloudClientId = clientId
                        }

                        // Capture OAuth token from Authorization header
                        if (!tokenCaptured.get()) {
                            val authHeader = request.requestHeaders["Authorization"]
                            if (authHeader != null && authHeader.startsWith("OAuth ")) {
                                val token = authHeader.removePrefix("OAuth ").trim()
                                if (token.length > 20) {
                                    storage.soundcloudToken = token
                                    if (tokenCaptured.compareAndSet(false, true)) {
                                        runOnUiThread { onTokenCaptured() }
                                    }
                                }
                            }
                        }
                    }

                    return null
                }
            }

            loadUrl("https://soundcloud.com")
        }
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
