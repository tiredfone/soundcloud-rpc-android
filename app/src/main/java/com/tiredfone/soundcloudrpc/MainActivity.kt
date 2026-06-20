package com.tiredfone.soundcloudrpc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tiredfone.soundcloudrpc.databinding.ActivityMainBinding
import im.delight.android.webview.AdvancedWebView

class MainActivity : AppCompatActivity(), AdvancedWebView.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: TokenStorage

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification is optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = TokenStorage(this)

        requestNotificationPermissionIfNeeded()
        setupWebView()
        setupSettingsFab()

        if (!storage.isConfigured()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else if (storage.rpcEnabled) {
            startService(Intent(this, RpcService::class.java))
        }
    }

    private fun setupWebView() {
        val bridge = JavaScriptBridge(applicationContext)

        binding.webView.apply {
            setListener(this@MainActivity, this@MainActivity)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false)
                builtInZoomControls = false
                // Full Chrome mobile UA — SoundCloud serves the full web player to Chrome
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            addJavascriptInterface(bridge, "Android")
            loadUrl("https://soundcloud.com")
        }
    }

    private fun setupSettingsFab() {
        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ── AdvancedWebView.Listener ──────────────────────────────────────────────

    override fun onPageStarted(url: String?, favicon: Bitmap?) {}

    override fun onPageFinished(url: String?) {
        // Re-inject tracker on every navigation (SPA navigations still fire this)
        binding.webView.evaluateJavascript(JavaScriptBridge.INJECTION_SCRIPT, null)
    }

    override fun onPageError(errorCode: Int, description: String?, failingUrl: String?) {}

    override fun onDownloadRequested(
        url: String?, suggestedFilename: String?, mimeType: String?,
        contentLength: Long, contentDisposition: String?, userAgent: String?
    ) {
        url?.let {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
            } catch (_: Exception) {}
        }
    }

    override fun onExternalPageRequest(url: String?) {
        url?.let {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
            } catch (_: Exception) {}
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

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
