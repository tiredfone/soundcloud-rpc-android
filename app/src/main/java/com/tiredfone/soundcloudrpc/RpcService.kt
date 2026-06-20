package com.tiredfone.soundcloudrpc

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RpcService : Service() {

    companion object {
        const val ACTION_UPDATE_TRACK = "com.tiredfone.soundcloudrpc.UPDATE_TRACK"
        const val ACTION_CLEAR_TRACK  = "com.tiredfone.soundcloudrpc.CLEAR_TRACK"
        const val EXTRA_TITLE   = "title"
        const val EXTRA_ARTIST  = "artist"
        const val EXTRA_ARTWORK = "artwork"

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
        startForeground(NOTIFICATION_ID, buildNotification("SoundCloud RPC", "Starting…"))
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
            updateNotification("SoundCloud RPC", status)
        })
        gateway?.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "onStartCommand action=${intent?.action}, configured=${storage.isConfigured()}, rpcEnabled=${storage.rpcEnabled}")
        if (!storage.isConfigured() || !storage.rpcEnabled) return START_STICKY

        when (intent?.action) {
            ACTION_UPDATE_TRACK -> {
                val title   = intent.getStringExtra(EXTRA_TITLE)   ?: return START_STICKY
                val artist  = intent.getStringExtra(EXTRA_ARTIST)  ?: return START_STICKY
                val artwork = intent.getStringExtra(EXTRA_ARTWORK)
                AppLogger.i(TAG, "UPDATE_TRACK: $title by $artist")
                val track   = TrackInfo(title, artist, artwork, true)
                gateway?.updatePresence(track)
                updateNotification(title, artist)
            }
            ACTION_CLEAR_TRACK -> {
                AppLogger.i(TAG, "CLEAR_TRACK")
                gateway?.clearPresence()
                updateNotification("SoundCloud RPC", "Nothing playing")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        gateway?.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(title: String, text: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
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
