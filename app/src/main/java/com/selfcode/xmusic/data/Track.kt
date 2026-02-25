package com.selfcode.xmusic.data

data class Track(
    val title: String,
    val artist: String,
    val url: String,
    val coverUrl: String,
    val duration: String
) {
    val fileName: String
        get() = buildString {
            append(artist.trim())
            append(" - ")
            append(title.trim())
            append(".mp3")
        }.replace(Regex("""[\\/:*?"<>|]"""), "")
}
