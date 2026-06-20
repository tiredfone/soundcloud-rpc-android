package com.tiredfone.soundcloudrpc

import android.os.Bundle
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.tiredfone.soundcloudrpc.databinding.ActivityDiscordLoginBinding

class DiscordLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscordLoginBinding
    private lateinit var storage: TokenStorage
    private var retryCount = 0

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
                // Use desktop Chrome UA — Discord mobile redirects to the app store
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36"
            }

            addJavascriptInterface(LoginBridge(), "AndroidLogin")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Once Discord's SPA redirects to the main app, extract token
                    if (url.contains("/channels") || url.contains("/app")) {
                        retryCount = 0
                        view.evaluateJavascript(EXTRACTION_JS, null)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val host = request.url.host ?: return false
                    // Keep everything in this WebView — Discord login may hit CDN domains
                    if (host.endsWith("discord.com") || host.endsWith("discordapp.com")) {
                        return false
                    }
                    return true // block external navigation
                }
            }

            loadUrl("https://discord.com/login")
        }
    }

    private inner class LoginBridge {
        @JavascriptInterface
        fun onTokenExtracted(token: String) {
            if (token.length < 20) return // sanity check — real tokens are long
            runOnUiThread {
                storage.discordToken = token
                setResult(RESULT_OK)
                finish()
            }
        }

        @JavascriptInterface
        fun onRetry() {
            retryCount++
            if (retryCount < 10) {
                binding.loginWebView.postDelayed({
                    binding.loginWebView.evaluateJavascript(EXTRACTION_JS, null)
                }, 1000)
            }
            // If we exhaust retries the user can just try saving manually
        }

        @JavascriptInterface
        fun onError(message: String) {
            android.util.Log.e("DiscordLogin", "Token extraction error: $message")
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

        // Injected after login redirect. Walks Discord's webpack module registry
        // to find the token store, which exposes a getToken() function.
        // Retries via AndroidLogin.onRetry() if the module hasn't loaded yet.
        private val EXTRACTION_JS = """
(function attempt() {
    try {
        if (!window.webpackChunkdiscord_app) { AndroidLogin.onRetry(); return; }
        var token = null;
        webpackChunkdiscord_app.push([[Math.floor(Math.random() * 1e9)], {}, function(req) {
            for (var id in req.c) {
                var mod = req.c[id];
                if (!mod || !mod.exports) continue;
                var ex = mod.exports;
                // Try default export first, then named export
                var fn = (ex.default && ex.default.getToken) || ex.getToken;
                if (typeof fn === 'function') {
                    token = fn.call(ex.default || ex);
                    break;
                }
            }
        }]);
        if (token && token.length > 20) {
            AndroidLogin.onTokenExtracted(token);
        } else {
            AndroidLogin.onRetry();
        }
    } catch(e) {
        AndroidLogin.onError(e.message || String(e));
        AndroidLogin.onRetry();
    }
})();
        """.trimIndent()
    }
}
