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
        if (html.length < 500) {
            logs.add("Ответ сервера: $html")
        } else {
            logs.add("Начало HTML: ${html.take(300)}")
        }

        val tracks = parseTracks(html, logs)
        return Pair(tracks, logs)
    }

    private fun parseTracks(html: String, logs: MutableList<String>): List<Track> {
        val tracks = mutableListOf<Track>()

        val downloadRegex = Regex("""href="(https://rus\.hitmotop\.com/get/music/[^"]+\.mp3)"""")
        val durationRegex = Regex("""<div class="track__fulltime">([^<]+)</div>""")

        val downloads = downloadRegex.findAll(html).map { it.groupValues[1] }.toList()
        val durations = durationRegex.findAll(html).map { it.groupValues[1].trim() }.toList()

        logs.add("Найдено download-ссылок: ${downloads.size}")
        logs.add("Найдено длительностей: ${durations.size}")

        val parts = html.split("""data-musmeta="""")
        logs.add("Блоков data-musmeta: ${parts.size - 1}")

        var idx = 0
        for (i in 1 until parts.size) {
            val part = parts[i]
            val endIdx = part.indexOf('"')
            if (endIdx < 0) {
                logs.add("[$i] Не найден конец атрибута")
                continue
            }

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
                } else {
                    logs.add("[$idx] Нет URL для: $artist - $title")
                }
            } catch (e: Exception) {
                logs.add("[$i] JSON ошибка: ${e.message} | raw: ${rawValue.take(80)}")
                idx++
            }
        }

        logs.add("Итого треков: ${tracks.size}")
        return tracks
    }
}
