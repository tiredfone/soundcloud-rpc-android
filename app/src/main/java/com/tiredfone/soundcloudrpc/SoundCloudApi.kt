package com.tiredfone.soundcloudrpc

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class SoundCloudApi(private val storage: TokenStorage) {

    private val client = OkHttpClient()
    private val gson = Gson()

    private fun String.withClientId(): String {
        val clientId = storage.soundcloudClientId ?: ""
        return if (contains("?")) "$this&client_id=$clientId" else "$this?client_id=$clientId"
    }

    private fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", "OAuth ${storage.soundcloudToken}")
        .header("Accept", "application/json")
        .build()

    suspend fun getStream(nextHref: String? = null): ScStreamPage? = withContext(Dispatchers.IO) {
        runCatching {
            val url = (nextHref ?: "https://api-v2.soundcloud.com/stream?limit=50").withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), ScStreamPage::class.java)
        }.getOrNull()
    }

    suspend fun searchTracks(query: String, nextHref: String? = null): ScSearchPage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = nextHref ?: run {
                    val q = URLEncoder.encode(query, "UTF-8")
                    "https://api-v2.soundcloud.com/search/tracks?q=$q&limit=30".withClientId()
                }
                val response = client.newCall(buildRequest(url)).execute()
                if (!response.isSuccessful) return@runCatching null
                gson.fromJson(response.body?.string(), ScSearchPage::class.java)
            }.getOrNull()
        }

    suspend fun getLikes(nextHref: String? = null): ScSearchPage? = withContext(Dispatchers.IO) {
        runCatching {
            val url = (nextHref
                ?: "https://api-v2.soundcloud.com/me/likes/tracks?limit=50").withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), ScSearchPage::class.java)
        }.getOrNull()
    }

    suspend fun resolveStreamUrl(track: ScTrack): String? = withContext(Dispatchers.IO) {
        runCatching {
            val transcodings = track.media?.transcodings ?: return@runCatching null
            val transcoding = transcodings.firstOrNull {
                it.format?.protocol?.equals("progressive", ignoreCase = true) == true
            } ?: transcodings.firstOrNull() ?: return@runCatching null

            val resolveUrl = transcoding.url?.withClientId() ?: return@runCatching null
            val response = client.newCall(buildRequest(resolveUrl)).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), JsonObject::class.java)?.get("url")?.asString
        }.getOrNull()
    }

    suspend fun getUsername(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/me".withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), JsonObject::class.java)?.get("username")?.asString
        }.getOrNull()
    }
}
