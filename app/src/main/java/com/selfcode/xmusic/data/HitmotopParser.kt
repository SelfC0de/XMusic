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

        // data-musmeta value contains HTML-encoded JSON: &quot; instead of "
        // Extract the raw attribute value (between the outer quotes of data-musmeta="...")
        val metaRegex = Regex("""data-musmeta="([^"]*(?:&quot;[^"]*)*+)"""")
        val downloadRegex = Regex("""class="track__download-btn"[^>]*href="([^"]+\.mp3)"""")
        val durationRegex = Regex("""<div class="track__fulltime">([^<]+)</div>""")

        val metas = metaRegex.findAll(html).map { it.groupValues[1] }.toList()
        val downloads = downloadRegex.findAll(html).map { it.groupValues[1] }.toList()
        val durations = durationRegex.findAll(html).map { it.groupValues[1].trim() }.toList()

        for (i in metas.indices) {
            try {
                val rawJson = metas[i]
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("\\/", "/")

                val json = JSONObject(rawJson)
                val artist = json.optString("artist", "Unknown")
                val title  = json.optString("title",  "Unknown")
                val img    = json.optString("img",    "")
                val downloadUrl = downloads.getOrElse(i) { "" }
                val duration = durations.getOrElse(i) { "" }

                if (downloadUrl.isNotEmpty()) {
                    tracks.add(Track(title, artist, downloadUrl, img, duration))
                }
            } catch (_: Exception) {}
        }
        return tracks
    }
}
