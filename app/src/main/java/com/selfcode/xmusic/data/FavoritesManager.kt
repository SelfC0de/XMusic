package com.selfcode.xmusic.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object FavoritesManager {

    private const val PREFS_NAME = "xmusic_favorites"
    private const val KEY_TRACKS = "favorite_tracks"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isFavorite(track: Track): Boolean {
        return getFavorites().any { it.url == track.url }
    }

    fun toggleFavorite(track: Track): Boolean {
        val list = getFavorites().toMutableList()
        val existing = list.indexOfFirst { it.url == track.url }
        return if (existing >= 0) {
            list.removeAt(existing)
            save(list)
            false
        } else {
            list.add(0, track)
            save(list)
            true
        }
    }

    fun removeFavorite(track: Track) {
        val list = getFavorites().toMutableList()
        list.removeAll { it.url == track.url }
        save(list)
    }

    fun getFavorites(): List<Track> {
        val json = prefs.getString(KEY_TRACKS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Track(
                    title = obj.optString("title", ""),
                    artist = obj.optString("artist", ""),
                    url = obj.optString("url", ""),
                    coverUrl = obj.optString("coverUrl", ""),
                    duration = obj.optString("duration", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Track>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(JSONObject().apply {
                put("title", t.title)
                put("artist", t.artist)
                put("url", t.url)
                put("coverUrl", t.coverUrl)
                put("duration", t.duration)
            })
        }
        prefs.edit().putString(KEY_TRACKS, arr.toString()).apply()
    }
}
