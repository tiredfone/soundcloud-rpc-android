package com.tiredfone.soundcloudrpc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tiredfone.soundcloudrpc.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: TokenStorage

    // Does NOT follow redirects — we return null on 3xx so the WebView follows
    // them natively, preserving its history stack and cookie jar.
    private val googleHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .build()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = TokenStorage(this)
        requestNotificationPermissionIfNeeded()
        setupWebView()

        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (!storage.isConfigured()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else if (storage.rpcEnabled) {
            startService(Intent(this, RpcService::class.java))
        }
    }

    private fun setupWebView() {
        val bridge = JavaScriptBridge(applicationContext)

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false)
                builtInZoomControls = false
                // Desktop Windows Chrome UA:
                //  • Removes the "wv" indicator so Google doesn't flag us as a WebView
                //  • Tells SoundCloud to serve the full desktop player (music data loads)
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Safari/537.36"
            }

            // Accept third-party cookies (SoundCloud + Google auth need these)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(bridge, "Android")

            webViewClient = object : WebViewClient() {

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val host = request.url.host ?: return null
                    // Android WebView injects X-Requested-With: <package-name> on every
                    // request. Google's OAuth server detects this and shows "browser not
                    // secure". Re-issue GET requests to Google auth via OkHttp without
                    // that header so sign-in proceeds normally.
                    if (request.method.equals("GET", ignoreCase = true) &&
                        host.endsWith("accounts.google.com")
                    ) {
                        return fetchWithoutWebViewHeaders(request)
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val host = request.url.host ?: return true
                    return when {
                        host.endsWith("soundcloud.com")      -> false
                        host.endsWith("sndcdn.com")          -> false
                        host.endsWith("accounts.google.com") -> false
                        host.endsWith("google.com")          -> false
                        host.endsWith("googleapis.com")      -> false
                        else -> {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            }
                            true
                        }
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(JavaScriptBridge.INJECTION_SCRIPT, null)
                }
            }

            loadUrl("https://soundcloud.com")
        }
    }

    /**
     * Re-issues a GET request via OkHttp, omitting the X-Requested-With header
     * that WebView adds automatically. Cookies are synced both ways so the Google
     * auth session stays consistent with the WebView's cookie jar.
     * Returns null on 3xx so the WebView follows redirects itself.
     */
    private fun fetchWithoutWebViewHeaders(request: WebResourceRequest): WebResourceResponse? {
        return try {
            val url = request.url.toString()
            val existingCookies = CookieManager.getInstance().getCookie(url)

            val okhttpRequest = Request.Builder()
                .url(url)
                .apply {
                    request.requestHeaders.forEach { (name, value) ->
                        if (!name.equals("x-requested-with", ignoreCase = true)) {
                            runCatching { header(name, value) }
                        }
                    }
                    if (!existingCookies.isNullOrEmpty()) {
                        header("Cookie", existingCookies)
                    }
                }
                .build()

            val response = googleHttpClient.newCall(okhttpRequest).execute()

            // Push any new cookies back into the WebView's cookie jar
            response.headers("Set-Cookie").forEach { cookie ->
                CookieManager.getInstance().setCookie(url, cookie)
            }

            // 3xx: let the WebView handle the redirect natively
            if (response.code in 300..399) return null

            val ct = response.header("Content-Type", "text/html; charset=utf-8")!!
            val mimeType = ct.substringBefore(";").trim()
            val charset  = ct.substringAfter("charset=", "utf-8").substringBefore(";").trim()

            val headers = response.headers.toMultimap()
                .mapValues { it.value.joinToString(", ") }
                .filterKeys { k ->
                    k.lowercase() !in setOf("set-cookie", "transfer-encoding", "content-encoding")
                }

            WebResourceResponse(
                mimeType, charset,
                response.code, response.message.ifEmpty { "OK" },
                headers,
                response.body?.byteStream()
            )
        } catch (_: Exception) {
            null // fall back to native WebView request
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        if (storage.isConfigured() && storage.rpcEnabled) {
            startService(Intent(this, RpcService::class.java))
        }
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.onDestroy()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
