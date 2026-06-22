package app.tiredfone.sclient

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RpcService : Service() {

    companion object {
        const val ACTION_UPDATE_TRACK = "app.tiredfone.sclient.UPDATE_TRACK"
        const val ACTION_CLEAR_TRACK  = "app.tiredfone.sclient.CLEAR_TRACK"
        const val EXTRA_TITLE      = "title"
        const val EXTRA_ARTIST     = "artist"
        const val EXTRA_ARTWORK    = "artwork"
        const val EXTRA_STARTED_AT = "started_at"
        const val EXTRA_ENDS_AT    = "ends_at"

        private const val CHANNEL_ID      = "soundcloud_rpc"
        private const val NOTIFICATION_ID = 1
        private const val TAG             = "RpcService"
    }

    private var gateway: DiscordGatewayClient? = null
    private lateinit var storage: TokenStorage

    override fun onCreate() {
        super.onCreate()
        storage = TokenStorage(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("SoundCloud RPC", "Starting…", null))
        AppLogger.i(TAG, "RpcService onCreate, configured=${storage.isConfigured()}, rpcEnabled=${storage.rpcEnabled}")
        startGateway()
    }

    private fun startGateway() {
        val token = storage.discordToken
        val appId = storage.applicationId
        AppLogger.i(TAG, "startGateway: token=${if (token != null) "set(${token.length} chars)" else "null"}, appId=${if (appId != null) "set" else "null"}")
        if (token == null || appId == null) {
            AppLogger.w(TAG, "startGateway: missing token or appId, aborting")
            return
        }
        gateway?.disconnect()
        gateway = DiscordGatewayClient(token, appId, onStatusChange = { status ->
            AppLogger.i(TAG, "Gateway status: $status")
            updateNotification("SoundCloud RPC", status, null)
        })
        gateway?.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "onStartCommand action=${intent?.action}, configured=${storage.isConfigured()}, rpcEnabled=${storage.rpcEnabled}, gatewayReady=${gateway != null}")
        if (!storage.isConfigured() || !storage.rpcEnabled) return START_STICKY

        // No action = service (re)started or settings saved; start gateway if not already running
        if (intent?.action == null && gateway == null) {
            AppLogger.i(TAG, "No action + no gateway — starting gateway")
            startGateway()
        }

        when (intent?.action) {
            ACTION_UPDATE_TRACK -> {
                val title     = intent.getStringExtra(EXTRA_TITLE)   ?: return START_STICKY
                val artist    = intent.getStringExtra(EXTRA_ARTIST)  ?: return START_STICKY
                val artwork   = intent.getStringExtra(EXTRA_ARTWORK)
                val startedAt = intent.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())
                val endsAt    = intent.getLongExtra(EXTRA_ENDS_AT, 0L).takeIf { it > 0L }
                AppLogger.i(TAG, "UPDATE_TRACK: $title by $artist")
                val track = TrackInfo(title, artist, artwork, true, startedAt, endsAt)
                gateway?.updatePresence(track)
                updateNotification(title, artist, null)
                if (artwork != null) {
                    Thread {
                        val bmp = loadBitmapFromUrl(artwork)
                        if (bmp != null) updateNotification(title, artist, bmp)
                    }.start()
                }
            }
            ACTION_CLEAR_TRACK -> {
                AppLogger.i(TAG, "CLEAR_TRACK")
                gateway?.clearPresence()
                updateNotification("SoundCloud RPC", "Nothing playing", null)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        gateway?.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(title: String, text: String, artwork: Bitmap?) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(title, text, artwork))
    }

    private fun buildNotification(title: String, text: String, artwork: Bitmap? = null): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(artwork)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun loadBitmapFromUrl(url: String): Bitmap? = try {
        java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        AppLogger.e(TAG, "Failed to load artwork: ${e.message}")
        null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SoundCloud RPC",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Discord Rich Presence for SoundCloud"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
}
