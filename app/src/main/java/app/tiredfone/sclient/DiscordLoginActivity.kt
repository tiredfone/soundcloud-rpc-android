package app.tiredfone.sclient

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import app.tiredfone.sclient.databinding.ActivityDiscordLoginBinding
import java.util.concurrent.atomic.AtomicBoolean

class DiscordLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscordLoginBinding
    private lateinit var storage: TokenStorage
    private val tokenCaptured = AtomicBoolean(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscordLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = TokenStorage(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if ((url.contains("discord.com/api") || url.contains("discordapp.com/api"))
                    && !tokenCaptured.get()
                ) {
                    val auth = request.requestHeaders["Authorization"]
                    if (!auth.isNullOrBlank() && !auth.startsWith("Bot ")) {
                        if (tokenCaptured.compareAndSet(false, true)) {
                            storage.discordToken = auth
                            runOnUiThread {
                                setResult(RESULT_OK)
                                finish()
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // If Discord redirects to /channels after login, the token is captured via API calls
            }
        }

        binding.webView.loadUrl("https://discord.com/login")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
