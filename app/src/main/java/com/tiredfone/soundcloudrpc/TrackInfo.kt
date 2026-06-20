package com.tiredfone.soundcloudrpc

data class TrackInfo(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val startedAt: Long = System.currentTimeMillis()
)
