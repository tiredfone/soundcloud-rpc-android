package com.tiredfone.soundcloudrpc

import com.google.gson.annotations.SerializedName

data class ScUser(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String,
    @SerializedName("avatar_url") val avatarUrl: String?
)

data class ScTranscodingFormat(
    @SerializedName("protocol") val protocol: String,
    @SerializedName("mime_type") val mimeType: String
)

data class ScTranscoding(
    @SerializedName("url") val url: String,
    @SerializedName("format") val format: ScTranscodingFormat
)

data class ScMedia(
    @SerializedName("transcodings") val transcodings: List<ScTranscoding>
)

data class ScTrack(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String,
    @SerializedName("user") val user: ScUser,
    @SerializedName("artwork_url") val artworkUrl: String?,
    @SerializedName("duration") val duration: Long,
    @SerializedName("media") val media: ScMedia
) {
    val artworkHigh: String?
        get() = artworkUrl?.replace("-large.", "-t500x500.")
}

data class ScStreamItem(
    @SerializedName("type") val type: String,
    @SerializedName("track") val track: ScTrack?
)

data class ScStreamPage(
    @SerializedName("collection") val collection: List<ScStreamItem>,
    @SerializedName("next_href") val nextHref: String?
)

data class ScSearchPage(
    @SerializedName("collection") val collection: List<ScTrack>,
    @SerializedName("next_href") val nextHref: String?
)
