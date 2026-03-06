package com.selfcode.xmusic.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MusicStorage {

    private const val PREFS = "xmusic_storage"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_REPEAT = "repeat_mode"

    private lateinit var prefs: SharedPreferences
    private lateinit var cacheDir: File

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cacheDir = File(context.filesDir, "music_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    // ========== REPEAT MODE ==========
    // 0 = play all once, 1 = repeat all, 2 = repeat one
    fun getRepeatMode(): Int = prefs.getInt(KEY_REPEAT, 0)
    fun setRepeatMode(mode: Int) = prefs.edit().putInt(KEY_REPEAT, mode).apply()

    // ========== FAVORITES ==========
    fun isFavorite(track: Track): Boolean = getFavorites().any { it.url == track.url }

    fun toggleFavorite(track: Track): Boolean {
        val list = getFavorites().toMutableList()
        val idx = list.indexOfFirst { it.url == track.url }
        return if (idx >= 0) {
            list.removeAt(idx)
            deleteCachedFile(track)
            saveFavorites(list)
            false
        } else {
            list.add(0, track)
            saveFavorites(list)
            true
        }
    }

    fun removeFavorite(track: Track) {
        val list = getFavorites().toMutableList()
        list.removeAll { it.url == track.url }
        deleteCachedFile(track)
        saveFavorites(list)
        removeTrackFromAllPlaylists(track)
    }

    fun getFavorites(): List<Track> = loadTracks(KEY_FAVORITES)

    private fun saveFavorites(list: List<Track>) = saveTracks(KEY_FAVORITES, list)

    // ========== PLAYLISTS ==========
    data class Playlist(
        val id: String,
        val name: String,
        val coverPath: String,
        val trackUrls: List<String>
    )

    fun getPlaylists(): List<Playlist> {
        val json = prefs.getString(KEY_PLAYLISTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val urls = obj.optJSONArray("trackUrls") ?: JSONArray()
                Playlist(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    coverPath = obj.optString("coverPath", ""),
                    trackUrls = (0 until urls.length()).map { urls.getString(it) }
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun createPlaylist(name: String, coverPath: String = ""): Playlist {
        val list = getPlaylists().toMutableList()
        val pl = Playlist(
            id = "pl_${System.currentTimeMillis()}",
            name = name,
            coverPath = coverPath,
            trackUrls = emptyList()
        )
        list.add(pl)
        savePlaylists(list)
        return pl
    }

    fun updatePlaylistCover(playlistId: String, coverPath: String) {
        val list = getPlaylists().toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(coverPath = coverPath)
            savePlaylists(list)
        }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        val list = getPlaylists().toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = newName)
            savePlaylists(list)
        }
    }

    fun deletePlaylist(playlistId: String) {
        val list = getPlaylists().toMutableList()
        list.removeAll { it.id == playlistId }
        savePlaylists(list)
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        val list = getPlaylists().toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            val urls = list[idx].trackUrls.toMutableList()
            if (!urls.contains(track.url)) {
                urls.add(track.url)
                list[idx] = list[idx].copy(trackUrls = urls)
                savePlaylists(list)
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, track: Track) {
        val list = getPlaylists().toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            val urls = list[idx].trackUrls.toMutableList()
            urls.remove(track.url)
            list[idx] = list[idx].copy(trackUrls = urls)
            savePlaylists(list)
        }
    }

    fun getPlaylistTracks(playlistId: String): List<Track> {
        val pl = getPlaylists().find { it.id == playlistId } ?: return emptyList()
        val favs = getFavorites()
        return pl.trackUrls.mapNotNull { url -> favs.find { it.url == url } }
    }

    private fun removeTrackFromAllPlaylists(track: Track) {
        val list = getPlaylists().toMutableList()
        var changed = false
        for (i in list.indices) {
            if (list[i].trackUrls.contains(track.url)) {
                val urls = list[i].trackUrls.toMutableList()
                urls.remove(track.url)
                list[i] = list[i].copy(trackUrls = urls)
                changed = true
            }
        }
        if (changed) savePlaylists(list)
    }

    private fun savePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        for (pl in list) {
            arr.put(JSONObject().apply {
                put("id", pl.id)
                put("name", pl.name)
                put("coverPath", pl.coverPath)
                put("trackUrls", JSONArray(pl.trackUrls))
            })
        }
        prefs.edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }

    // ========== MP3 CACHE ==========
    fun getCachedFile(track: Track): File? {
        val f = File(cacheDir, track.safeFileName)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun getCacheFile(track: Track): File = File(cacheDir, track.safeFileName)

    fun isTrackCached(track: Track): Boolean = getCachedFile(track) != null

    private fun deleteCachedFile(track: Track) {
        val f = File(cacheDir, track.safeFileName)
        if (f.exists()) f.delete()
    }

    // ========== TRACK SERIALIZATION ==========
    private fun loadTracks(key: String): List<Track> {
        val json = prefs.getString(key, "[]") ?: "[]"
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
        } catch (_: Exception) { emptyList() }
    }

    private fun saveTracks(key: String, list: List<Track>) {
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
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
