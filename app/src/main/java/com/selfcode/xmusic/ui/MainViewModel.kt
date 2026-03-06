package com.selfcode.xmusic.ui

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.selfcode.xmusic.data.HitmotopParser
import com.selfcode.xmusic.data.Track
import com.selfcode.xmusic.utils.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    object LoadingMore : SearchState()
    data class Success(val tracks: List<Track>, val hasMore: Boolean) : SearchState()
    data class Error(val message: String) : SearchState()
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Progress(val percent: Int, val fileName: String) : DownloadState()
    data class Done(val fileName: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState = _searchState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    private val _logMessages = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val logMessages = _logMessages.asSharedFlow()

    private var currentQuery = ""
    private var currentStart = 0
    private val allTracks = mutableListOf<Track>()
    private val pageSize = 48

    private fun log(msg: String) {
        viewModelScope.launch { _logMessages.emit(msg) }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        currentQuery = query
        currentStart = 0
        allTracks.clear()
        viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = SearchState.Loading
            try {
                val (results, logs) = HitmotopParser.searchWithLogs(query, 0)
                logs.forEach { log(it) }
                allTracks.addAll(results)
                currentStart = pageSize
                val hasMore = results.size >= pageSize
                _searchState.value = if (allTracks.isEmpty())
                    SearchState.Error("Ничего не найдено")
                else
                    SearchState.Success(allTracks.toList(), hasMore)
            } catch (e: Exception) {
                log("Exception: ${e.message}")
                _searchState.value = SearchState.Error("Ошибка: ${e.message}")
            }
        }
    }

    fun loadMore() {
        if (currentQuery.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = SearchState.LoadingMore
            try {
                val (results, logs) = HitmotopParser.searchWithLogs(currentQuery, currentStart)
                logs.forEach { log(it) }
                allTracks.addAll(results)
                currentStart += pageSize
                val hasMore = results.size >= pageSize
                _searchState.value = SearchState.Success(allTracks.toList(), hasMore)
            } catch (e: Exception) {
                log("LoadMore error: ${e.message}")
                _searchState.value = SearchState.Success(allTracks.toList(), false)
            }
        }
    }

    fun download(track: Track, savePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadState.value = DownloadState.Progress(0, track.fileName)
            try {
                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    downloadQ(track) { _downloadState.value = DownloadState.Progress(it, track.fileName) }
                } else {
                    downloadLegacy(track, savePath) { _downloadState.value = DownloadState.Progress(it, track.fileName) }
                }
                _downloadState.value = if (success)
                    DownloadState.Done(track.fileName)
                else
                    DownloadState.Error("Ошибка загрузки")
            } catch (e: Exception) {
                _downloadState.value = DownloadState.Error(e.message ?: "Ошибка")
            }
        }
    }

    private fun downloadQ(track: Track, onProgress: (Int) -> Unit): Boolean {
        val ctx = getApplication<Application>()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, track.fileName)
            put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                val tempFile = File(ctx.cacheDir, "tmp_${System.currentTimeMillis()}.mp3")
                val ok = Downloader.download(track.url, tempFile, onProgress)
                if (ok) tempFile.inputStream().use { it.copyTo(out) }
                tempFile.delete()
                ok
            } ?: false
        } finally {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private fun downloadLegacy(track: Track, savePath: String, onProgress: (Int) -> Unit): Boolean {
        val dir = if (savePath.isNotEmpty()) File(savePath)
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "SelfMusic")
        val dest = File(dir, track.fileName)
        return Downloader.download(track.url, dest, onProgress)
    }

    fun resetDownload() {
        _downloadState.value = DownloadState.Idle
    }

    fun currentTracks(): List<Track> = allTracks.toList()
}
