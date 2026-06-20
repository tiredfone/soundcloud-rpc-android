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

    companion object {
        private const val TAG = "SoundCloudApi"
    }

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
            AppLogger.i(TAG, "getStream → ${url.substringBefore('?')}")
            val response = client.newCall(buildRequest(url)).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "getStream failed: ${response.code} — $body")
                return@runCatching null
            }
            AppLogger.i(TAG, "getStream OK ${response.code}")
            gson.fromJson(body, ScStreamPage::class.java)
        }.getOrElse { e -> AppLogger.e(TAG, "getStream exception: ${e.message}"); null }
    }

    suspend fun searchTracks(query: String, nextHref: String? = null): ScSearchPage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = nextHref ?: run {
                    val q = URLEncoder.encode(query, "UTF-8")
                    "https://api-v2.soundcloud.com/search/tracks?q=$q&limit=30".withClientId()
                }
                AppLogger.i(TAG, "searchTracks q=$query")
                val response = client.newCall(buildRequest(url)).execute()
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    AppLogger.e(TAG, "searchTracks failed: ${response.code} — $body")
                    return@runCatching null
                }
                AppLogger.i(TAG, "searchTracks OK ${response.code}")
                gson.fromJson(body, ScSearchPage::class.java)
            }.getOrElse { e -> AppLogger.e(TAG, "searchTracks exception: ${e.message}"); null }
        }

    suspend fun getLikes(nextHref: String? = null): ScSearchPage? = withContext(Dispatchers.IO) {
        runCatching {
            val url = (nextHref
                ?: "https://api-v2.soundcloud.com/me/likes/tracks?limit=50").withClientId()
            AppLogger.i(TAG, "getLikes → ${url.substringBefore('?')}")
            val response = client.newCall(buildRequest(url)).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "getLikes failed: ${response.code} — $body")
                return@runCatching null
            }
            AppLogger.i(TAG, "getLikes OK ${response.code}, body length=${body?.length}")
            val json = gson.fromJson(body, com.google.gson.JsonObject::class.java)
                ?: return@runCatching null
            val collection = json.getAsJsonArray("collection")
                ?: return@runCatching ScSearchPage(emptyList(), null)
            AppLogger.i(TAG, "getLikes collection size=${collection.size()}, first keys=${collection.takeIf { it.size() > 0 }?.get(0)?.asJsonObject?.keySet()}")
            val nextHrefResult = json.get("next_href")
                ?.takeIf { !it.isJsonNull }?.asString
            // /me/likes/tracks can return either wrapped {"kind":"like","track":{...}} items
            // or direct track objects depending on the API version / token scope
            val tracks = if (collection.size() > 0 &&
                collection[0].asJsonObject.has("track")) {
                AppLogger.i(TAG, "getLikes: wrapped format detected")
                collection.mapNotNull { item ->
                    item.asJsonObject.getAsJsonObject("track")?.let {
                        gson.fromJson(it, ScTrack::class.java)
                    }
                }
            } else {
                AppLogger.i(TAG, "getLikes: direct format detected")
                collection.mapNotNull { gson.fromJson(it, ScTrack::class.java) }
            }
            AppLogger.i(TAG, "getLikes parsed ${tracks.size} tracks")
            ScSearchPage(collection = tracks, nextHref = nextHrefResult)
        }.getOrElse { e -> AppLogger.e(TAG, "getLikes exception: ${e.message}"); null }
    }

    suspend fun getTrack(id: Long): ScTrack? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/tracks/$id".withClientId()
            AppLogger.i(TAG, "getTrack id=$id")
            val response = client.newCall(buildRequest(url)).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "getTrack failed: ${response.code} — $body")
                return@runCatching null
            }
            gson.fromJson(body, ScTrack::class.java)
        }.getOrElse { e -> AppLogger.e(TAG, "getTrack exception: ${e.message}"); null }
    }

    suspend fun resolveStreamUrl(track: ScTrack): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedTrack = if (track.media?.transcodings.isNullOrEmpty()) {
                AppLogger.i(TAG, "resolveStreamUrl: no transcodings on track ${track.id}, fetching full track")
                getTrack(track.id) ?: return@runCatching null
            } else {
                track
            }

            val transcodings = resolvedTrack.media?.transcodings ?: run {
                AppLogger.e(TAG, "resolveStreamUrl: still no transcodings after getTrack for ${track.id}")
                return@runCatching null
            }
            AppLogger.i(TAG, "resolveStreamUrl: ${transcodings.size} transcodings, protocols=${transcodings.map { it.format?.protocol }}")
            val transcoding = transcodings.firstOrNull {
                it.format?.protocol?.equals("progressive", ignoreCase = true) == true
            } ?: transcodings.firstOrNull() ?: return@runCatching null

            val resolveUrl = (transcoding.url ?: return@runCatching null).withClientId() + "&country_code=US"
            AppLogger.i(TAG, "resolveStreamUrl: resolving ${transcoding.format?.protocol}")
            val response = client.newCall(buildRequest(resolveUrl)).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "resolveStreamUrl failed: ${response.code} — $body")
                return@runCatching null
            }
            val url = gson.fromJson(body, JsonObject::class.java)?.get("url")?.asString
            AppLogger.i(TAG, "resolveStreamUrl: got stream URL=${url?.take(60)}...")
            url
        }.getOrElse { e -> AppLogger.e(TAG, "resolveStreamUrl exception: ${e.message}"); null }
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
            AppLogger.i(TAG, "getPlaylists → ${url.substringBefore('?')}")
            val response = client.newCall(buildRequest(url)).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "getPlaylists failed: ${response.code} — $body")
                return@runCatching null
            }
            AppLogger.i(TAG, "getPlaylists OK ${response.code}, body length=${body?.length}")
            val page = gson.fromJson(body, ScPlaylistsPage::class.java)
            AppLogger.i(TAG, "getPlaylists parsed ${page?.collection?.size} playlists")
            page
        }.getOrElse { e -> AppLogger.e(TAG, "getPlaylists exception: ${e.message}"); null }
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
