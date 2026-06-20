package com.tiredfone.soundcloudrpc

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SoundCloudApi(private val storage: TokenStorage) {

    private val client = OkHttpClient()
    private val gson = Gson()

    private fun String.withClientId(): String {
        val clientId = storage.soundcloudClientId ?: ""
        return if (contains("?")) "$this&client_id=$clientId" else "$this?client_id=$clientId"
    }

    private fun buildRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("Authorization", "OAuth ${storage.soundcloudToken}")
            .header("Accept", "application/json")
            .build()
    }

    suspend fun getStream(nextHref: String? = null): ScStreamPage? = withContext(Dispatchers.IO) {
        runCatching {
            val url = (nextHref ?: "https://api-v2.soundcloud.com/stream?limit=20").withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            gson.fromJson(body, ScStreamPage::class.java)
        }.getOrNull()
    }

    suspend fun searchTracks(query: String): ScSearchPage? = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api-v2.soundcloud.com/search/tracks?q=$encoded&limit=20".withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            gson.fromJson(body, ScSearchPage::class.java)
        }.getOrNull()
    }

    suspend fun getLikes(nextHref: String? = null): ScSearchPage? = withContext(Dispatchers.IO) {
        runCatching {
            val url = (nextHref ?: "https://api-v2.soundcloud.com/me/likes/tracks?limit=20").withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            gson.fromJson(body, ScSearchPage::class.java)
        }.getOrNull()
    }

    suspend fun resolveStreamUrl(track: ScTrack): String? = withContext(Dispatchers.IO) {
        runCatching {
            val transcodings = track.media.transcodings
            // Prefer progressive transcoding; fall back to first available
            val transcoding = transcodings.firstOrNull {
                it.format.protocol.equals("progressive", ignoreCase = true)
            } ?: transcodings.firstOrNull() ?: return@runCatching null

            val resolveUrl = transcoding.url.withClientId()
            val response = client.newCall(buildRequest(resolveUrl)).execute()
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            val json = gson.fromJson(body, JsonObject::class.java)
            json.get("url")?.asString
        }.getOrNull()
    }

    suspend fun getUsername(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/me".withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            val json = gson.fromJson(body, JsonObject::class.java)
            json.get("username")?.asString
        }.getOrNull()
    }
}
