package com.selfcode.xmusic.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object HitmotopParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun search(query: String): List<Track> = searchWithLogs(query).first

    fun searchWithLogs(query: String): Pair<List<Track>, List<String>> {
        val logs = mutableListOf<String>()
        val url = "https://rus.hitmotop.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            .header("Accept-Encoding", "identity")
            .header("Connection", "keep-alive")
            .header("Cache-Control", "max-age=0")
            .build()

        val response = client.newCall(request).execute()
        val html = response.use { resp ->
            logs.add("HTTP ${resp.code} | Content-Type: ${resp.header("Content-Type")}")
            resp.body?.string() ?: ""
        }

        logs.add("Длина ответа: ${html.length} символов")

        val tracks = parseTracks(html, logs)
        return Pair(tracks, logs)
    }

    private fun parseTracks(html: String, logs: MutableList<String>): List<Track> {
        val tracks = mutableListOf<Track>()

        val downloadRegex = Regex("""href="(https://rus\.hitmotop\.com/get/music/[^"]+\.mp3)"""")
        val durationRegex = Regex("""<div class="track__fulltime">([^<]+)</div>""")

        val downloads = downloadRegex.findAll(html).map { it.groupValues[1] }.toList()
        val durations = durationRegex.findAll(html).map { it.groupValues[1].trim() }.toList()

        logs.add("Download-ссылок: ${downloads.size}")

        // Диагностика — найти data-musmeta в сыром виде
        val rawIdx = html.indexOf("data-musmeta")
        if (rawIdx < 0) {
            logs.add("data-musmeta НЕ НАЙДЕН в HTML!")
            logs.add("HTML[0..200]: ${html.take(200)}")
            return tracks
        }

        val sample = html.substring(rawIdx, minOf(rawIdx + 120, html.length))
        logs.add("sample: $sample")

        // Определяем разделитель
        val afterAttr = html.substring(rawIdx + "data-musmeta".length)
        val delimiter = when {
            afterAttr.startsWith("=\"") -> "data-musmeta=\""
            afterAttr.startsWith("='")  -> "data-musmeta='"
            afterAttr.startsWith("=")   -> "data-musmeta="
            else -> {
                logs.add("Неизвестный формат после data-musmeta: [${afterAttr.take(10)}]")
                return tracks
            }
        }
        val closingQuote = if (delimiter.endsWith("\"")) '"' else '\''
        logs.add("Разделитель: [$delimiter], закрывающий: [$closingQuote]")

        val parts = html.split(delimiter)
        logs.add("Блоков: ${parts.size - 1}")

        var idx = 0
        for (i in 1 until parts.size) {
            val part = parts[i]
            val endIdx = part.indexOf(closingQuote)
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
            } catch (e: Exception) {
                logs.add("[$i] JSON err: ${e.message?.take(60)}")
                idx++
            }
        }

        logs.add("Итого треков: ${tracks.size}")
        return tracks
    }
}
