package com.tiredfone.soundcloudrpc

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DiscordGatewayClient(
    private val token: String,
    private val applicationId: String,
    private val onStatusChange: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "DiscordGateway"
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_PRESENCE_UPDATE = 3
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var heartbeatInterval = 41250L
    private var lastSequence: Int? = null
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var isReady = false
    private var pendingTrack: TrackInfo? = null

    fun connect() {
        val url = resumeUrl ?: GATEWAY_URL
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, GatewayListener())
        Log.d(TAG, "Connecting to $url")
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        scope.cancel()
        isReady = false
    }

    fun updatePresence(track: TrackInfo) {
        pendingTrack = track
        if (isReady) sendPresenceUpdate(track)
    }

    fun clearPresence() {
        pendingTrack = null
        if (!isReady) return
        send(JsonObject().apply {
            addProperty("op", OP_PRESENCE_UPDATE)
            add("d", JsonObject().apply {
                add("since", JsonNull.INSTANCE)
                add("activities", JsonArray())
                addProperty("status", "online")
                addProperty("afk", false)
            })
        })
    }

    private fun sendPresenceUpdate(track: TrackInfo) {
        val largeImage = if (!track.artworkUrl.isNullOrEmpty()) {
            "mp:external/${track.artworkUrl.removePrefix("https://")}"
        } else {
            "soundcloud"
        }

        val activity = JsonObject().apply {
            addProperty("name", "SoundCloud")
            addProperty("type", 2) // "Listening to"
            addProperty("application_id", applicationId)
            addProperty("details", track.title)
            addProperty("state", track.artist)
            add("timestamps", JsonObject().apply {
                addProperty("start", track.startedAt)
            })
            add("assets", JsonObject().apply {
                addProperty("large_image", largeImage)
                addProperty("large_text", "SoundCloud")
            })
        }

        send(JsonObject().apply {
            addProperty("op", OP_PRESENCE_UPDATE)
            add("d", JsonObject().apply {
                add("since", JsonNull.INSTANCE)
                add("activities", JsonArray().apply { add(activity) })
                addProperty("status", "online")
                addProperty("afk", false)
            })
        })
    }

    private fun identify() {
        send(JsonObject().apply {
            addProperty("op", OP_IDENTIFY)
            add("d", JsonObject().apply {
                addProperty("token", token)
                add("properties", JsonObject().apply {
                    addProperty("os", "android")
                    addProperty("browser", "Discord Android")
                    addProperty("device", "android")
                })
                addProperty("compress", false)
                add("presence", JsonObject().apply {
                    addProperty("status", "online")
                    addProperty("since", 0)
                    add("activities", JsonArray())
                    addProperty("afk", false)
                })
            })
        })
    }

    private fun resume() {
        val sid = sessionId ?: return identify()
        send(JsonObject().apply {
            addProperty("op", OP_RESUME)
            add("d", JsonObject().apply {
                addProperty("token", token)
                addProperty("session_id", sid)
                val seq = lastSequence
                if (seq != null) addProperty("seq", seq) else add("seq", JsonNull.INSTANCE)
            })
        })
    }

    private fun startHeartbeat(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay((interval * Random.nextDouble()).toLong())
            while (isActive) {
                sendHeartbeat()
                delay(interval)
            }
        }
    }

    private fun sendHeartbeat() {
        send(JsonObject().apply {
            addProperty("op", OP_HEARTBEAT)
            val seq = lastSequence
            if (seq != null) addProperty("d", seq) else add("d", JsonNull.INSTANCE)
        })
    }

    private fun send(payload: JsonObject) {
        val json = payload.toString()
        val sent = webSocket?.send(json)
        if (sent == false) Log.w(TAG, "Failed to send: $json")
    }

    private fun scheduleReconnect(delayMs: Long = 5000L) {
        scope.launch {
            delay(delayMs)
            if (isActive) connect()
        }
    }

    private inner class GatewayListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened")
            onStatusChange("Connecting…")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val payload = JsonParser.parseString(text).asJsonObject
                val op = payload["op"].asInt
                val d = payload["d"]
                val seq = payload["s"]?.takeIf { !it.isJsonNull }?.asInt
                val t = payload["t"]?.takeIf { !it.isJsonNull }?.asString

                if (seq != null) lastSequence = seq

                when (op) {
                    OP_HELLO -> {
                        heartbeatInterval = d.asJsonObject["heartbeat_interval"].asLong
                        startHeartbeat(heartbeatInterval)
                        if (sessionId != null) resume() else identify()
                    }
                    OP_HEARTBEAT -> sendHeartbeat()
                    OP_HEARTBEAT_ACK -> Log.d(TAG, "Heartbeat ACK")
                    OP_RECONNECT -> {
                        Log.d(TAG, "Reconnect requested")
                        webSocket.close(4000, "Reconnect")
                    }
                    OP_INVALID_SESSION -> {
                        val resumable = d?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                        Log.d(TAG, "Invalid session, resumable=$resumable")
                        if (!resumable) sessionId = null
                        val delay = if (resumable) 1000L else (1000L + Random.nextLong(4000L))
                        scope.launch {
                            delay(delay)
                            if (resumable && sessionId != null) resume() else identify()
                        }
                    }
                    OP_DISPATCH -> handleDispatch(t, d.asJsonObject)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message", e)
            }
        }

        private fun handleDispatch(event: String?, data: JsonObject) {
            when (event) {
                "READY" -> {
                    sessionId = data["session_id"].asString
                    resumeUrl = data["resume_gateway_url"]?.takeIf { !it.isJsonNull }?.asString
                    isReady = true
                    Log.d(TAG, "Ready! Session=$sessionId")
                    onStatusChange("Connected")
                    pendingTrack?.let { sendPresenceUpdate(it) }
                }
                "RESUMED" -> {
                    isReady = true
                    Log.d(TAG, "Resumed session")
                    onStatusChange("Connected")
                    pendingTrack?.let { sendPresenceUpdate(it) }
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isReady = false
            heartbeatJob?.cancel()
            Log.d(TAG, "Closed: $code $reason")
            onStatusChange("Reconnecting…")
            if (code != 1000) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isReady = false
            heartbeatJob?.cancel()
            Log.e(TAG, "Failure: ${t.message}")
            onStatusChange("Reconnecting…")
            scheduleReconnect(8000L)
        }
    }
}
