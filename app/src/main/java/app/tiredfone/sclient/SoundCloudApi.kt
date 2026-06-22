package app.tiredfone.sclient

import android.content.Context
import android.webkit.CookieManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLEncoder

class SoundCloudApi(private val storage: TokenStorage, private val context: Context? = null) {

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

    // Attempts to refresh the access token using the stored refresh token.
    // Returns true if the token was refreshed successfully.
    suspend fun refreshTokenIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = storage.soundcloudRefreshToken ?: return@withContext false
        val clientId = storage.soundcloudClientId ?: return@withContext false
        AppLogger.i(TAG, "Attempting token refresh")
        runCatching {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
            val req = Request.Builder()
                .url("https://api.soundcloud.com/oauth2/token")
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string()
            AppLogger.i(TAG, "Token refresh response: ${resp.code} — $respBody")
            if (!resp.isSuccessful) return@runCatching false
            val json = gson.fromJson(respBody, JsonObject::class.java) ?: return@runCatching false
            val newToken = json.get("access_token")?.takeIf { !it.isJsonNull }?.asString
            val newRefresh = json.get("refresh_token")?.takeIf { !it.isJsonNull }?.asString
            if (newToken.isNullOrBlank()) return@runCatching false
            storage.soundcloudToken = newToken
            if (!newRefresh.isNullOrBlank()) storage.soundcloudRefreshToken = newRefresh
            AppLogger.i(TAG, "Token refreshed successfully")
            true
        }.getOrElse { e -> AppLogger.e(TAG, "Token refresh exception: ${e.message}"); false }
    }

    // Executes a request; on 401, refreshes the token and retries once.
    private suspend fun executeWithRefresh(buildReq: () -> Request): okhttp3.Response? {
        var resp = client.newCall(buildReq()).execute()
        if (resp.code == 401) {
            AppLogger.w(TAG, "Got 401, attempting token refresh and retry")
            resp.close()
            if (refreshTokenIfNeeded()) {
                resp = client.newCall(buildReq()).execute()
            } else {
                return null
            }
        }
        return resp
    }

    suspend fun getStream(nextHref: String? = null): ScStreamPage? = withContext(Dispatchers.IO) {
        runCatching {
            val url = (nextHref ?: "https://api-v2.soundcloud.com/stream?limit=50").withClientId()
            AppLogger.i(TAG, "getStream → ${url.substringBefore('?')}")
            val response = executeWithRefresh { buildRequest(url) } ?: return@runCatching null
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
            // /me/likes returns 404 for many client IDs; /users/{id}/likes is more reliable
            val url = if (nextHref != null) {
                nextHref.withClientId()
            } else {
                val userId = getOrFetchUserId() ?: return@runCatching null
                "https://api-v2.soundcloud.com/users/$userId/likes?limit=50".withClientId()
            }
            AppLogger.i(TAG, "getLikes → ${url.substringBefore('?')}")
            val response = executeWithRefresh { buildRequest(url) } ?: return@runCatching null
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
            // /me/likes returns {kind:"like", track:{...}} or {kind:"like", playlist:{...}}
            // Extract only track items, per-item (not just checking the first element)
            val tracks = collection.mapNotNull { item ->
                val obj = item.asJsonObject
                when {
                    obj.has("track") -> obj.getAsJsonObject("track")?.let {
                        gson.fromJson(it, ScTrack::class.java)
                    }
                    obj.has("id") -> gson.fromJson(obj, ScTrack::class.java) // direct format
                    else -> null
                }
            }
            AppLogger.i(TAG, "getLikes parsed ${tracks.size} tracks")
            ScSearchPage(collection = tracks, nextHref = nextHrefResult)
        }.getOrElse { e -> AppLogger.e(TAG, "getLikes exception: ${e.message}"); null }
    }

    private suspend fun getOrFetchUserId(): Long? = withContext(Dispatchers.IO) {
        val cached = storage.soundcloudUserId
        if (cached > 0) return@withContext cached
        runCatching {
            val url = "https://api-v2.soundcloud.com/me".withClientId()
            AppLogger.i(TAG, "Fetching user ID from /me")
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            val id = gson.fromJson(response.body?.string(), JsonObject::class.java)?.get("id")?.asLong
            if (id != null && id > 0) {
                storage.soundcloudUserId = id
                AppLogger.i(TAG, "Cached user ID: $id")
            }
            id
        }.getOrElse { e -> AppLogger.e(TAG, "getOrFetchUserId exception: ${e.message}"); null }
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

    suspend fun getUsername(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/me".withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), JsonObject::class.java)?.get("username")?.asString
        }.getOrNull()
    }

    suspend fun getPlaylistTracks(playlistId: Long, nextHref: String? = null): ScSearchPage? = withContext(Dispatchers.IO) {
        runCatching {
            val url = (nextHref ?: "https://api-v2.soundcloud.com/playlists/$playlistId/tracks?limit=50").withClientId()
            AppLogger.i(TAG, "getPlaylistTracks id=$playlistId")
            val response = executeWithRefresh { buildRequest(url) } ?: return@runCatching null
            if (response.isSuccessful) {
                val body = response.body?.string()
                AppLogger.i(TAG, "getPlaylistTracks OK ${response.code}, body length=${body?.length}")
                return@runCatching gson.fromJson(body, ScSearchPage::class.java)
            }
            val errBody = response.body?.string()
            AppLogger.w(TAG, "getPlaylistTracks /tracks returned ${response.code} — $errBody, trying playlist fallback")
            // Can't paginate in fallback mode — only applies to first load
            if (nextHref != null) return@runCatching null

            // Fallback: GET /playlists/{id} returns full playlist object with up to 5 full tracks + stubs
            val playlist = getPlaylist(playlistId) ?: run {
                AppLogger.e(TAG, "getPlaylistTracks fallback: getPlaylist also failed")
                return@runCatching null
            }
            val allTracks = playlist.tracks ?: return@runCatching ScSearchPage(emptyList(), null)
            AppLogger.i(TAG, "getPlaylistTracks fallback: playlist has ${allTracks.size} tracks (trackCount=${playlist.trackCount})")
            val stubIds = allTracks.filter { it.duration <= 0 }.map { it.id }
            if (stubIds.isEmpty()) return@runCatching ScSearchPage(collection = allTracks, nextHref = null)

            // Batch-fetch stubs via /tracks?ids=...
            val fetchedById = mutableMapOf<Long, ScTrack>()
            for (batch in stubIds.chunked(50)) {
                val batchUrl = "https://api-v2.soundcloud.com/tracks?ids=${batch.joinToString(",")}".withClientId()
                val batchResp = executeWithRefresh { buildRequest(batchUrl) } ?: continue
                if (batchResp.isSuccessful) {
                    gson.fromJson(batchResp.body?.string(), Array<ScTrack>::class.java)?.forEach { fetchedById[it.id] = it }
                }
            }
            val resolved = allTracks.map { t -> if (t.duration > 0) t else fetchedById[t.id] ?: t }
            AppLogger.i(TAG, "getPlaylistTracks fallback done: ${resolved.size} tracks (${fetchedById.size} stubs resolved)")
            ScSearchPage(collection = resolved, nextHref = null)
        }.getOrElse { e -> AppLogger.e(TAG, "getPlaylistTracks exception: ${e.message}"); null }
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
            // /me/playlists/liked_and_owned returns 404 for most client IDs; /users/{id}/playlists is reliable
            val url = if (nextHref != null) {
                nextHref.withClientId()
            } else {
                val userId = getOrFetchUserId() ?: return@runCatching null
                "https://api-v2.soundcloud.com/users/$userId/playlists?limit=50".withClientId()
            }
            AppLogger.i(TAG, "getPlaylists → ${url.substringBefore('?')}")
            val response = executeWithRefresh { buildRequest(url) } ?: return@runCatching null
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

    suspend fun getUser(userId: Long): ScUser? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/users/$userId".withClientId()
            val response = client.newCall(buildRequest(url)).execute()
            if (!response.isSuccessful) return@runCatching null
            gson.fromJson(response.body?.string(), ScUser::class.java)
        }.getOrNull()
    }

    suspend fun getUserTracks(userId: Long, nextHref: String? = null): ScSearchPage? = withContext(Dispatchers.IO) {
        runCatching {
            val url = (nextHref ?: "https://api-v2.soundcloud.com/users/$userId/tracks?limit=30").withClientId()
            AppLogger.i(TAG, "getUserTracks userId=$userId")
            val response = executeWithRefresh { buildRequest(url) } ?: return@runCatching null
            val body = response.body?.string()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "getUserTracks failed: ${response.code}")
                return@runCatching null
            }
            gson.fromJson(body, ScSearchPage::class.java)
        }.getOrElse { e -> AppLogger.e(TAG, "getUserTracks exception: ${e.message}"); null }
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

    private fun webCookies(): String? = runCatching {
        CookieManager.getInstance().getCookie("https://soundcloud.com")
    }.getOrNull()

    private fun likeRequest(method: String, trackId: Long): Request {
        val clientId = storage.soundcloudClientId ?: ""
        val cookies = webCookies()
        return Request.Builder()
            .url("https://api-v2.soundcloud.com/me/track_likes/$trackId?client_id=$clientId")
            .method(method, if (method == "PUT") "{}".toRequestBody("application/json".toMediaType()) else null)
            .header("Authorization", "OAuth ${storage.soundcloudToken}")
            .header("Origin", "https://soundcloud.com")
            .header("Referer", "https://soundcloud.com/")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
            .build()
    }

    suspend fun likeTrack(trackId: Long): Boolean {
        val ctx = context
        val token = storage.soundcloudToken
        val clientId = storage.soundcloudClientId
        if (ctx != null && token != null && clientId != null) {
            AppLogger.i(TAG, "likeTrack $trackId via WebView")
            return LikeWebHelper.get(ctx).like(trackId, token, clientId)
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val resp = client.newCall(likeRequest("PUT", trackId)).execute()
                AppLogger.i(TAG, "likeTrack $trackId: ${resp.code} — ${resp.body?.string()?.take(200)}")
                resp.isSuccessful
            }.getOrElse { e -> AppLogger.e(TAG, "likeTrack exception: ${e.message}"); false }
        }
    }

    suspend fun unlikeTrack(trackId: Long): Boolean {
        val ctx = context
        val token = storage.soundcloudToken
        val clientId = storage.soundcloudClientId
        if (ctx != null && token != null && clientId != null) {
            AppLogger.i(TAG, "unlikeTrack $trackId via WebView")
            return LikeWebHelper.get(ctx).unlike(trackId, token, clientId)
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val resp = client.newCall(likeRequest("DELETE", trackId)).execute()
                AppLogger.i(TAG, "unlikeTrack $trackId: ${resp.code} — ${resp.body?.string()?.take(200)}")
                resp.isSuccessful
            }.getOrElse { e -> AppLogger.e(TAG, "unlikeTrack exception: ${e.message}"); false }
        }
    }

    suspend fun getRelatedTracks(trackId: Long): ScSearchPage? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api-v2.soundcloud.com/tracks/$trackId/related?limit=10".withClientId()
            AppLogger.i(TAG, "getRelatedTracks id=$trackId")
            val response = client.newCall(buildRequest(url)).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "getRelatedTracks failed: ${response.code}")
                return@runCatching null
            }
            gson.fromJson(body, ScSearchPage::class.java)
        }.getOrElse { e -> AppLogger.e(TAG, "getRelatedTracks exception: ${e.message}"); null }
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
