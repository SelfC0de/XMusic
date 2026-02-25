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

        // Extract all <li class="tracks__item ..."> blocks
        val liRegex = Regex("""<li[^>]+class="tracks__item[^"]*"[^>]*data-musmeta="([^>]*?)"[^>]*>""")
        // data-musmeta value: everything between data-musmeta=" and the next " that is followed by >
        // Since &quot; is used inside, the value ends at the first unescaped "
        // Simple: split by data-musmeta=" and take until next "
        
        val downloadRegex = Regex("""href="(https://rus\.hitmotop\.com/get/music/[^"]+\.mp3)"""")
        val durationRegex = Regex("""<div class="track__fulltime">([^<]+)</div>""")

        val downloads = downloadRegex.findAll(html).map { it.groupValues[1] }.toList()
        val durations = durationRegex.findAll(html).map { it.groupValues[1].trim() }.toList()

        // Split html by "data-musmeta=\"" to extract each meta value
        val parts = html.split("""data-musmeta="""")
        // parts[0] = before first, parts[1..n] = starting from the value

        var idx = 0
        for (i in 1 until parts.size) {
            val part = parts[i]
            // Find the end of the attribute value: first " not preceded by &quot
            // The value contains &quot; but not literal "
            val endIdx = part.indexOf('"')
            if (endIdx < 0) continue

            val rawValue = part.substring(0, endIdx)
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("\\/", "/")

            try {
                val json = JSONObject(rawValue)
                val artist = json.optString("artist", "Unknown")
                val title  = json.optString("title",  "Unknown")
                val img    = json.optString("img",    "")
                val downloadUrl = downloads.getOrElse(idx) { "" }
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
