package app.tiredfone.sclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_TOGGLE_PLAY = "ACTION_TOGGLE_PLAY"
        const val ACTION_SKIP_NEXT = "ACTION_SKIP_NEXT"
        const val ACTION_SKIP_PREVIOUS = "ACTION_SKIP_PREVIOUS"
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "sc_player"

        var pendingTrack: ScTrack? = null
        var pendingUrl: String? = null
        var pendingAutoNext = false
    }

    interface PlayerCallback {
        fun onTrackChanged(track: ScTrack?)
        fun onPlayStateChanged(isPlaying: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var api: SoundCloudApi

    lateinit var player: ExoPlayer
    var currentTrack: ScTrack? = null
    var autoNextEnabled = false
    val trackQueue: ArrayDeque<ScTrack> = ArrayDeque()
    private val callbacks = mutableListOf<PlayerCallback>()

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        api = SoundCloudApi(TokenStorage(this), this)
        LikeWebHelper.get(this)  // warm up the headless WebView

        mediaSession = MediaSessionCompat(this, "SClient").apply {
            isActive = true
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    notifyPlayState(isPlaying)
                    currentTrack?.let { updateNotification(it) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        playRelated()
                    }
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
                autoNextEnabled = pendingAutoNext
                playTrack(track, url)
            }
            ACTION_TOGGLE_PLAY -> {
                if (player.isPlaying) player.pause() else player.play()
            }
            ACTION_SKIP_NEXT -> skipToNext()
            ACTION_SKIP_PREVIOUS -> skipToPrevious()
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

    fun skipToNext() {
        playRelated()
    }

    fun skipToPrevious() {
        player.seekTo(0)
    }

    fun enqueueTrack(track: ScTrack) {
        trackQueue.addLast(track)
    }

    private fun playRelated() {
        // Queue takes priority over auto-next
        if (trackQueue.isNotEmpty()) {
            val next = trackQueue.removeFirst()
            serviceScope.launch {
                val url = api.resolveStreamUrl(next) ?: return@launch
                playTrack(next, url)
            }
            return
        }
        if (!autoNextEnabled) return
        val trackId = currentTrack?.id ?: return
        serviceScope.launch {
            val related = api.getRelatedTracks(trackId)
            val next = related?.collection?.firstOrNull() ?: return@launch
            val url = api.resolveStreamUrl(next) ?: return@launch
            playTrack(next, url)
        }
    }

    fun addCallback(cb: PlayerCallback) {
        if (!callbacks.contains(cb)) callbacks.add(cb)
    }

    fun removeCallback(cb: PlayerCallback) {
        callbacks.remove(cb)
    }

    private fun notifyTrackChanged(track: ScTrack) {
        callbacks.toList().forEach { it.onTrackChanged(track) }
    }

    private fun notifyPlayState(isPlaying: Boolean) {
        callbacks.toList().forEach { it.onPlayStateChanged(isPlaying) }
        val track = currentTrack ?: return
        when {
            isPlaying -> {
                val positionMs = player.currentPosition
                val durationMs = player.duration.takeIf { it > 0 }
                val nowMs = System.currentTimeMillis()
                val startedAt = nowMs - positionMs
                val endsAt = durationMs?.let { startedAt + it }
                startService(Intent(this, RpcService::class.java).apply {
                    action = RpcService.ACTION_UPDATE_TRACK
                    putExtra(RpcService.EXTRA_TITLE, track.displayTitle)
                    putExtra(RpcService.EXTRA_ARTIST, track.displayArtist)
                    putExtra(RpcService.EXTRA_ARTWORK, track.artworkHigh)
                    putExtra(RpcService.EXTRA_STARTED_AT, startedAt)
                    endsAt?.let { putExtra(RpcService.EXTRA_ENDS_AT, it) }
                })
            }
            !player.playWhenReady -> {
                startService(Intent(this, RpcService::class.java).apply {
                    action = RpcService.ACTION_CLEAR_TRACK
                })
            }
        }
    }

    private fun updateNotification(track: ScTrack) {
        notificationManager.notify(NOTIFICATION_ID, buildMediaNotification(track, null))
        val artworkUrl = track.artworkHigh ?: track.artworkUrl ?: return
        serviceScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val request = ImageRequest.Builder(this@PlayerService)
                        .data(artworkUrl)
                        .size(512, 512)
                        .allowHardware(false)
                        .build()
                    val result = ImageLoader(this@PlayerService).execute(request)
                    (result as? SuccessResult)?.drawable?.let { (it as? BitmapDrawable)?.bitmap }
                }.getOrNull()
            }
            if (bitmap != null) {
                notificationManager.notify(NOTIFICATION_ID, buildMediaNotification(track, bitmap))
            }
        }
    }

    private fun buildMediaNotification(track: ScTrack, artwork: Bitmap?): Notification {
        val isPlaying = player.isPlaying
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, NowPlayingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prevIntent = PendingIntent.getService(
            this, 10,
            Intent(this, PlayerService::class.java).apply { action = ACTION_SKIP_PREVIOUS },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this, 11,
            Intent(this, PlayerService::class.java).apply { action = ACTION_TOGGLE_PLAY },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextIntent = PendingIntent.getService(
            this, 12,
            Intent(this, PlayerService::class.java).apply { action = ACTION_SKIP_NEXT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mediaStyle = MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setStyle(mediaStyle)
            .setContentTitle(track.displayTitle)
            .setContentText(track.displayArtist)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(artwork)
            .setContentIntent(tapIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setSilent(true)
            .addAction(R.drawable.ic_skip_previous, "Previous", prevIntent)
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Play",
                toggleIntent
            )
            .addAction(R.drawable.ic_skip_next, "Next", nextIntent)
            .build()
    }

    private fun buildIdleNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SClient")
            .setContentText("Ready")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
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
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
