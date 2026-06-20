package com.tiredfone.soundcloudrpc

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
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
            val body = response.body?.string()
            if (!response.isSuccessful) {
                android.util.Log.e("SoundCloudApi", "getLikes failed: ${response.code} - $body")
                return@runCatching null
            }
            val json = gson.fromJson(body, com.google.gson.JsonObject::class.java)
                ?: return@runCatching null
            val collection = json.getAsJsonArray("collection")
                ?: return@runCatching ScSearchPage(emptyList(), null)
            val nextHrefResult = json.get("next_href")
                ?.takeIf { !it.isJsonNull }?.asString
            // /me/likes/tracks can return either wrapped {"kind":"like","track":{...}} items
            // or direct track objects depending on the API version / token scope
            val tracks = if (collection.size() > 0 &&
                collection[0].asJsonObject.has("track")) {
                collection.mapNotNull { item ->
                    item.asJsonObject.getAsJsonObject("track")?.let {
                        gson.fromJson(it, ScTrack::class.java)
                    }
                }
            } else {
                collection.mapNotNull { gson.fromJson(it, ScTrack::class.java) }
            }
            ScSearchPage(collection = tracks, nextHref = nextHrefResult)
        }.getOrNull()
    }

    suspend fun getTrack(id: Long): ScTrack? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/tracks/$id".withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), ScTrack::class.java)
        }.getOrNull()
    }

    suspend fun resolveStreamUrl(track: ScTrack): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedTrack = if (track.media?.transcodings.isNullOrEmpty()) {
                getTrack(track.id) ?: return@runCatching null
            } else {
                track
            }

            val transcodings = resolvedTrack.media?.transcodings ?: return@runCatching null
            val transcoding = transcodings.firstOrNull {
                it.format?.protocol?.equals("progressive", ignoreCase = true) == true
            } ?: transcodings.firstOrNull() ?: return@runCatching null

            val resolveUrl = (transcoding.url ?: return@runCatching null).withClientId() + "&country_code=US"
            val response = client.newCall(buildRequest(resolveUrl)).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), JsonObject::class.java)?.get("url")?.asString
        }.getOrNull()
    }

    suspend fun likeTrack(trackId: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/me/likes/tracks/$trackId".withClientId()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "OAuth ${storage.soundcloudToken}")
                .put("".toRequestBody())
                .build()
            client.newCall(req).execute().isSuccessful
        }.getOrElse { false }
    }

    suspend fun unlikeTrack(trackId: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/me/likes/tracks/$trackId".withClientId()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "OAuth ${storage.soundcloudToken}")
                .delete()
                .build()
            client.newCall(req).execute().isSuccessful
        }.getOrElse { false }
    }

    suspend fun getUsername(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/me".withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), JsonObject::class.java)?.get("username")?.asString
        }.getOrNull()
    }

    suspend fun getPlaylist(id: Long): ScPlaylist? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/playlists/$id".withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), ScPlaylist::class.java)
        }.getOrNull()
    }

    suspend fun getPlaylists(nextHref: String? = null): ScPlaylistsPage? = withContext(Dispatchers.IO) {
        runCatching {
            val url = (nextHref ?: "https://api-v2.soundcloud.com/me/playlists?limit=50").withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                android.util.Log.e("SoundCloudApi", "getPlaylists failed: ${response.code} - $body")
                return@runCatching null
            }
            gson.fromJson(body, ScPlaylistsPage::class.java)
        }.getOrNull()
    }

    suspend fun createPlaylist(title: String, trackIds: List<Long> = emptyList()): ScPlaylist? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/playlists".withClientId()
            val tracksJson = com.google.gson.JsonArray().apply {
                trackIds.forEach { id -> add(com.google.gson.JsonObject().apply { addProperty("id", id) }) }
            }
            val bodyJson = com.google.gson.JsonObject().apply {
                add("playlist", com.google.gson.JsonObject().apply {
                    addProperty("title", title)
                    addProperty("sharing", "public")
                    add("tracks", tracksJson)
                })
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "OAuth ${storage.soundcloudToken}")
                .header("Content-Type", "application/json; charset=utf-8")
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val response = client.newCall(req).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), ScPlaylist::class.java)
        }.getOrNull()
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long, existingIds: List<Long>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/playlists/$playlistId".withClientId()
            val tracksJson = com.google.gson.JsonArray().apply {
                (existingIds + trackId).distinct().forEach { id ->
                    add(com.google.gson.JsonObject().apply { addProperty("id", id) })
                }
            }
            val bodyJson = com.google.gson.JsonObject().apply {
                add("playlist", com.google.gson.JsonObject().apply { add("tracks", tracksJson) })
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "OAuth ${storage.soundcloudToken}")
                .header("Content-Type", "application/json; charset=utf-8")
                .put(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().isSuccessful
        }.getOrElse { false }
    }
}
