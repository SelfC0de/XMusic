package com.selfcode.xmusic.data

import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class RecognitionResult(
    val artist: String,
    val title: String,
    val album: String,
    val coverUrl: String,
    val songLink: String
)

object MusicRecognizer {

    private const val API_TOKEN = "22ad2a4225ef1eea9766b425f1a51512"
    private const val API_URL = "https://api.audd.io/"
    private const val RECORD_DURATION_MS = 7000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var recorder: MediaRecorder? = null

    suspend fun recognize(cacheDir: File): Result<RecognitionResult?> = withContext(Dispatchers.IO) {
        val audioFile = File(cacheDir, "recognize_${System.currentTimeMillis()}.m4a")
        try {
            recordAudio(audioFile)
            val result = sendToApi(audioFile)
            audioFile.delete()
            Result.success(result)
        } catch (e: Exception) {
            audioFile.delete()
            Result.failure(e)
        }
    }

    private suspend fun recordAudio(outputFile: File) {
        withContext(Dispatchers.Main) {
            @Suppress("DEPRECATION")
            recorder = MediaRecorder()
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
        }
        delay(RECORD_DURATION_MS)
        withContext(Dispatchers.Main) {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
        }
    }

    fun cancelRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
    }

    private fun sendToApi(file: File): RecognitionResult? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("api_token", API_TOKEN)
            .addFormDataPart("return", "apple_music,spotify")
            .addFormDataPart("file", file.name,
                file.asRequestBody("audio/mp4".toMediaType()))
            .build()

        val request = Request.Builder()
            .url(API_URL)
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val json = response.use { it.body?.string() ?: "" }
        val root = JSONObject(json)

        if (root.optString("status") != "success") {
            val error = root.optJSONObject("error")
            val msg = error?.optString("error_message") ?: "Ошибка распознавания"
            throw Exception(msg)
        }

        val result = root.optJSONObject("result") ?: return null

        val artist = result.optString("artist", "")
        val title = result.optString("title", "")
        val album = result.optString("album", "")
        val songLink = result.optString("song_link", "")

        var coverUrl = ""
        val appleMusic = result.optJSONObject("apple_music")
        if (appleMusic != null) {
            val artwork = appleMusic.optJSONObject("artwork")
            if (artwork != null) {
                coverUrl = artwork.optString("url", "")
                    .replace("{w}", "600")
                    .replace("{h}", "600")
            }
        }
        if (coverUrl.isEmpty()) {
            val spotify = result.optJSONObject("spotify")
            if (spotify != null) {
                val spotAlbum = spotify.optJSONObject("album")
                val images = spotAlbum?.optJSONArray("images")
                if (images != null && images.length() > 0) {
                    coverUrl = images.getJSONObject(0).optString("url", "")
                }
            }
        }

        return RecognitionResult(artist, title, album, coverUrl, songLink)
    }
}
