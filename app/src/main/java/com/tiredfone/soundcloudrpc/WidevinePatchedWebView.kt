package com.tiredfone.soundcloudrpc

import android.content.Context
import android.net.Uri
import android.os.Message
import android.util.AttributeSet
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import im.delight.android.webview.AdvancedWebView

/**
 * AdvancedWebView subclass that adds Widevine DRM (EME) support.
 *
 * AdvancedWebView installs its own private mWebChromeClient for file-upload handling
 * and always makes that the *active* client. It does not override onPermissionRequest,
 * so the default WebChromeClient behaviour applies — which calls request.deny().
 * Widevine (RESOURCE_PROTECTED_MEDIA_ID) therefore never gets granted.
 *
 * We patch this by:
 *  1. Retrieving the private mWebChromeClient field via reflection after init.
 *  2. Wrapping it in a WidevineWebChromeClient that grants DRM permissions and
 *     delegates everything else (file chooser, console, progress…) to the original.
 *  3. Writing the wrapper back into the field, then triggering
 *     AdvancedWebView.setWebChromeClient(null) so it calls
 *     super.setWebChromeClient(mWebChromeClient) → making the wrapper the active client.
 */
class WidevinePatchedWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AdvancedWebView(context, attrs) {

    init {
        patchWidevineSupport()
    }

    private fun patchWidevineSupport() {
        try {
            // Walk up the class hierarchy to find the mWebChromeClient field
            var field: java.lang.reflect.Field? = null
            var clazz: Class<*>? = javaClass.superclass // AdvancedWebView
            while (clazz != null && field == null) {
                try {
                    field = clazz.getDeclaredField("mWebChromeClient")
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
            if (field == null) {
                Log.w(TAG, "mWebChromeClient field not found — Widevine may not work")
                return
            }
            field.isAccessible = true

            val original = field.get(this) as? WebChromeClient ?: return
            val patched = WidevineWebChromeClient(original)

            // Replace the field so that when AdvancedWebView's own code does
            // super.setWebChromeClient(mWebChromeClient), it uses our patched version.
            field.set(this, patched)

            // Re-trigger the active-client assignment by calling AdvancedWebView's own
            // setWebChromeClient — it will call super.setWebChromeClient(mWebChromeClient)
            // which now points to `patched`.
            setWebChromeClient(null)

            Log.d(TAG, "Widevine DRM patch applied successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Widevine DRM patch failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WidevinePatchedWebView"
    }

    // Delegates everything to the original AdvancedWebView internal client but
    // grants all media/DRM permissions (including Widevine L1/L3 via EME).
    private class WidevineWebChromeClient(
        private val delegate: WebChromeClient
    ) : WebChromeClient() {

        override fun onPermissionRequest(request: PermissionRequest) {
            // Grant Widevine (RESOURCE_PROTECTED_MEDIA_ID) and anything else the page needs
            request.grant(request.resources)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            delegate.onPermissionRequestCanceled(request)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) =
            delegate.onProgressChanged(view, newProgress)

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?) =
            delegate.onConsoleMessage(consoleMessage)

        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
        ) = delegate.onCreateWindow(view, isDialog, isUserGesture, resultMsg)

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ) = delegate.onShowFileChooser(webView, filePathCallback, fileChooserParams)
    }
}
