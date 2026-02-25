package com.selfcode.xmusic.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object HitmotopParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun search(query: String): List<Track> {
        val url = "https://rus.hitmotop.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            .build()
        val html = client.newCall(request).execute().use { it.body?.string() ?: "" }
        return parseTracks(html)
    }

    private fun parseTracks(html: String): List<Track> {
        val tracks = mutableListOf<Track>()

        // Pattern: data-musmeta="{...}" — contains artist, title, img
        // data-musmeta value uses HTML entities (&quot; -> "), escaped slashes (\/)
        val itemRegex = Regex(
            """data-musmeta="(\{[^"]+\})".*?class="track__download-btn"[^>]*href="([^"]+)"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val durationRegex = Regex("""<div class="track__fulltime">([^<]+)</div>""")
        val durations = durationRegex.findAll(html).map { it.groupValues[1].trim() }.toList()
        var idx = 0

        for (m in itemRegex.findAll(html)) {
            try {
                val rawJson = m.groupValues[1]
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("\\/", "/")

                val json = JSONObject(rawJson)
                val artist = json.optString("artist", "Unknown")
                val title  = json.optString("title",  "Unknown")
                val img    = json.optString("img",    "")

                // Direct download link from href (full mp3, not preview cut)
                val downloadUrl = m.groupValues[2]
                val duration = durations.getOrElse(idx) { "" }
                idx++

                if (downloadUrl.isNotEmpty()) {
                    tracks.add(Track(title, artist, downloadUrl, img, duration))
                }
            } catch (_: Exception) { idx++ }
        }
        return tracks
    }
}
