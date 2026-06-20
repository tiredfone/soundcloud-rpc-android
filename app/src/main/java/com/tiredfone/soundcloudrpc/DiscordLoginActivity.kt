package com.tiredfone.soundcloudrpc

import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.tiredfone.soundcloudrpc.databinding.ActivityDiscordLoginBinding
import java.util.concurrent.atomic.AtomicBoolean

class DiscordLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscordLoginBinding
    private lateinit var storage: TokenStorage

    // Guard against saving the token from multiple simultaneous requests
    private val tokenSaved = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscordLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Login with Discord"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        storage = TokenStorage(this)
        setupWebView()
    }

    private fun setupWebView() {
        with(binding.loginWebView) {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Mobile UA gets redirected to the Play Store — use desktop Chrome
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                /**
                 * Called on a background thread for every request the page makes.
                 * After Discord login the SPA fires off API calls that carry the user's
                 * token in the Authorization header — we read it here rather than trying
                 * to rip it out of Discord's webpack bundle via JS (which breaks whenever
                 * Discord ships a new build).
                 */
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val host = request.url.host ?: return null
                    if (!tokenSaved.get() && host.endsWith("discord.com")) {
                        val auth = request.requestHeaders["Authorization"]
                        // Real Discord user tokens are long base64 strings (70+ chars)
                        if (!auth.isNullOrBlank() && auth.length > 30) {
                            saveTokenAndFinish(auth)
                        }
                    }
                    return null // let the request proceed normally
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val host = request.url.host ?: return true
                    // Keep discord.com navigation inside this WebView
                    return !host.endsWith("discord.com") && !host.endsWith("discordapp.com")
                }
            }

            loadUrl("https://discord.com/login")
        }
    }

    private fun saveTokenAndFinish(token: String) {
        if (tokenSaved.compareAndSet(false, true)) {
            storage.discordToken = token
            runOnUiThread {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.loginWebView.canGoBack()) {
            binding.loginWebView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val RESULT_LOGGED_IN = RESULT_OK
    }
}
