package app.tiredfone.sclient

import com.google.gson.annotations.SerializedName

// All reference fields are nullable — Gson bypasses Kotlin constructors (uses Unsafe) so
// non-nullable fields still end up null when the JSON key is absent, causing NPEs.
// Nullable types with safe-call operators prevent those crashes.

data class ScUser(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("username") val username: String? = null,
    @SerializedName("avatar_url") val avatarUrl: String? = null
)

data class ScTranscodingFormat(
    @SerializedName("protocol") val protocol: String? = null,
    @SerializedName("mime_type") val mimeType: String? = null
)

data class ScTranscoding(
    @SerializedName("url") val url: String? = null,
    @SerializedName("format") val format: ScTranscodingFormat? = null
)

data class ScMedia(
    @SerializedName("transcodings") val transcodings: List<ScTranscoding>? = null
)

data class ScTrack(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("title") val title: String? = null,
    @SerializedName("user") val user: ScUser? = null,
    @SerializedName("artwork_url") val artworkUrl: String? = null,
    @SerializedName("duration") val duration: Long = 0,
    @SerializedName("media") val media: ScMedia? = null,
    @SerializedName("permalink_url") val permalinkUrl: String? = null
) {
    val artworkHigh: String?
        get() = artworkUrl?.replace("-large.", "-t500x500.")

    val displayTitle: String get() = title ?: ""
    val displayArtist: String get() = user?.username ?: ""
}

data class ScStreamItem(
    @SerializedName("type") val type: String? = null,
    @SerializedName("track") val track: ScTrack? = null
)

data class ScStreamPage(
    @SerializedName("collection") val collection: List<ScStreamItem>? = null,
    @SerializedName("next_href") val nextHref: String? = null
)

data class ScSearchPage(
    @SerializedName("collection") val collection: List<ScTrack>? = null,
    @SerializedName("next_href") val nextHref: String? = null
)

data class ScPlaylist(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("title") val title: String? = null,
    @SerializedName("track_count") val trackCount: Int = 0,
    @SerializedName("artwork_url") val artworkUrl: String? = null,
    @SerializedName("tracks") val tracks: List<ScTrack>? = null,
    @SerializedName("user") val user: ScUser? = null,
    @SerializedName("duration") val duration: Long = 0
) {
    val displayTitle: String get() = title ?: "Untitled Playlist"
    val artworkHigh: String? get() = artworkUrl?.replace("-large.", "-t500x500.")
}

data class ScPlaylistsPage(
    @SerializedName("collection") val collection: List<ScPlaylist>? = null,
    @SerializedName("next_href") val nextHref: String? = null
)
