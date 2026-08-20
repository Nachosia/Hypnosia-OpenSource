package dev.hypnosia.media

data class MediaInfo(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val thumbnailPath: String? = null
)
