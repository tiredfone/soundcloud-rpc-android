package app.tiredfone.sclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "ACTION_PLAY"
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "sc_player"

        var pendingTrack: ScTrack? = null
        var pendingUrl: String? = null
    }

    interface PlayerCallback {
        fun onTrackChanged(track: ScTrack?)
        fun onPlayStateChanged(isPlaying: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()
    lateinit var player: ExoPlayer
    var currentTrack: ScTrack? = null
    private val callbacks = mutableListOf<PlayerCallback>()

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    notifyPlayState(isPlaying)
                }
            })
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildIdleNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val track = pendingTrack ?: return START_STICKY
                val url = pendingUrl ?: return START_STICKY
                playTrack(track, url)
            }
            "TOGGLE_PLAY" -> {
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        return START_STICKY
    }

    fun playTrack(track: ScTrack, url: String) {
        currentTrack = track
        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        notifyTrackChanged(track)
        updateNotification(track)
    }

    fun addCallback(cb: PlayerCallback) {
        if (!callbacks.contains(cb)) callbacks.add(cb)
    }

    fun removeCallback(cb: PlayerCallback) {
        callbacks.remove(cb)
    }

    private fun notifyTrackChanged(track: ScTrack) {
        callbacks.toList().forEach { it.onTrackChanged(track) }
        // Don't push to RPC here — wait for isPlaying=true to avoid clearing during buffering.
        // The RPC update fires in notifyPlayState when the player actually starts.
    }

    private fun notifyPlayState(isPlaying: Boolean) {
        callbacks.toList().forEach { it.onPlayStateChanged(isPlaying) }
        currentTrack?.let { updateNotification(it) }
        val track = currentTrack ?: return
        when {
            isPlaying -> {
                // Player is actually outputting audio — set RPC
                startService(Intent(this, RpcService::class.java).apply {
                    action = RpcService.ACTION_UPDATE_TRACK
                    putExtra(RpcService.EXTRA_TITLE, track.displayTitle)
                    putExtra(RpcService.EXTRA_ARTIST, track.displayArtist)
                    putExtra(RpcService.EXTRA_ARTWORK, track.artworkHigh)
                })
            }
            !player.playWhenReady -> {
                // User explicitly paused (playWhenReady=false). Buffering keeps playWhenReady=true
                // so this branch is skipped during buffering, preserving the RPC presence.
                startService(Intent(this, RpcService::class.java).apply {
                    action = RpcService.ACTION_CLEAR_TRACK
                })
            }
        }
    }

    private fun buildIdleNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SoundCloud")
            .setContentText("Ready")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(track: ScTrack) {
        val isPlaying = player.isPlaying
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, NowPlayingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlayerService::class.java).apply { action = "TOGGLE_PLAY" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(track.title)
            .setContentText(track.displayArtist)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(playPauseIcon, playPauseTitle, toggleIntent)
            .build()

        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SoundCloud Player",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SoundCloud music playback"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
