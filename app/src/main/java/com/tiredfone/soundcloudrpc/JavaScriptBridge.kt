package com.tiredfone.soundcloudrpc

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface

class JavaScriptBridge(private val context: Context) {

    @JavascriptInterface
    fun onTrackChanged(title: String, artist: String, artwork: String, isPlaying: Boolean) {
        val intent = Intent(context, RpcService::class.java).apply {
            action = if (isPlaying && title.isNotEmpty() && artist.isNotEmpty()) {
                RpcService.ACTION_UPDATE_TRACK
            } else {
                RpcService.ACTION_CLEAR_TRACK
            }
            putExtra(RpcService.EXTRA_TITLE, title)
            putExtra(RpcService.EXTRA_ARTIST, artist)
            putExtra(RpcService.EXTRA_ARTWORK, artwork.ifEmpty { null })
        }
        context.startService(intent)
    }

    @JavascriptInterface
    fun onPlaybackStopped() {
        context.startService(Intent(context, RpcService::class.java).apply {
            action = RpcService.ACTION_CLEAR_TRACK
        })
    }

    companion object {
        // Injected into WebView on every page load. Polls SoundCloud DOM for now-playing state.
        val INJECTION_SCRIPT = """
(function() {
    if (window.__scRpcInjected) return;
    window.__scRpcInjected = true;

    var lastState = '';

    function getBgImageUrl(el) {
        if (!el) return '';
        var bg = window.getComputedStyle(el).backgroundImage || el.style.backgroundImage;
        if (bg && bg !== 'none') {
            var m = bg.match(/url\(["']?([^"')]+)["']?\)/);
            return m ? m[1] : '';
        }
        return '';
    }

    function poll() {
        var titleEl =
            document.querySelector('.playbackSoundBadge__titleLink span[aria-hidden="true"]') ||
            document.querySelector('.playbackSoundBadge__titleLink span:not(.sc-visuallyhidden)') ||
            document.querySelector('[class*="playbackSoundBadge__title"] span') ||
            document.querySelector('[class*="playerWidget__trackTitle"]') ||
            document.querySelector('[class*="nowPlaying__title"]');

        var artistEl =
            document.querySelector('.playbackSoundBadge__lightLink') ||
            document.querySelector('[class*="playbackSoundBadge__light"] a') ||
            document.querySelector('[class*="playerWidget__artist"] a') ||
            document.querySelector('[class*="nowPlaying__artist"]');

        var playBtn =
            document.querySelector('.playControls__play') ||
            document.querySelector('[aria-label="Play"]') ||
            document.querySelector('[aria-label="Pause"]') ||
            document.querySelector('[class*="playControls"] button[class*="play"]');

        var artworkEl =
            document.querySelector('.playbackSoundBadge__avatar .sc-artwork span') ||
            document.querySelector('.playbackSoundBadge__avatar span span') ||
            document.querySelector('[class*="playerWidget__artwork"] span') ||
            document.querySelector('[class*="nowPlaying__artwork"] span');

        var title  = titleEl  ? titleEl.textContent.trim()  : '';
        var artist = artistEl ? artistEl.textContent.trim() : '';
        var artwork = artworkEl ? getBgImageUrl(artworkEl) : '';

        // Convert small artwork to large (t67x67 -> t500x500)
        artwork = artwork.replace(/-t\d+x\d+\./, '-t500x500.');

        var isPlaying = false;
        if (playBtn) {
            var label = playBtn.getAttribute('aria-label') || '';
            isPlaying = label.toLowerCase() === 'pause' ||
                        playBtn.classList.contains('playing');
        }

        var state = title + '|' + artist + '|' + isPlaying + '|' + artwork;
        if (state !== lastState) {
            lastState = state;
            if (title && artist) {
                Android.onTrackChanged(title, artist, artwork, isPlaying);
            } else if (!title && lastState !== '') {
                Android.onPlaybackStopped();
            }
        }
    }

    setInterval(poll, 1500);

    var observer = new MutationObserver(function() { setTimeout(poll, 200); });
    observer.observe(document.documentElement, {
        subtree: true, childList: true, attributes: true,
        attributeFilter: ['class', 'aria-label', 'style']
    });

    poll();
})();
        """.trimIndent()
    }
}
