package com.selfcode.xmusic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val body: String
)

object UpdateChecker {

    private const val API_URL = "https://api.github.com/repos/SelfC0de/XMusic/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            val json = response.use { it.body?.string() ?: "" }

            if (response.code == 404) return@withContext Result.success(null)
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))

            val root = JSONObject(json)
            val tagName = root.optString("tag_name", "")
            val body = root.optString("body", "")
            val version = tagName
                .replace("pre-release-v", "")
                .replace("v", "")
                .trim()

            var apkUrl = ""
            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            if (version.isEmpty()) return@withContext Result.success(null)

            Result.success(UpdateInfo(version, apkUrl, body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isNewer(current: String, remote: String): Boolean {
        try {
            val cur = current.split(".").map { it.toIntOrNull() ?: 0 }
            val rem = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(cur.size, rem.size)
            for (i in 0 until maxLen) {
                val c = cur.getOrElse(i) { 0 }
                val r = rem.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (_: Exception) {}
        return false
    }
}
