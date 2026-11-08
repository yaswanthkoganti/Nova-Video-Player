package com.nova.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal OpenSubtitles.com REST v1 client. Needs the user's own API key (+ account for downloads). */
class OpenSubtitles(private val apiKey: String, private val user: String, private val pass: String) {
    data class Result(val fileId: Long, val name: String, val lang: String, val downloads: Int)

    private val base = "https://api.opensubtitles.com/api/v1"

    private fun call(path: String, body: JSONObject? = null, token: String? = null): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Api-Key", apiKey)
        c.setRequestProperty("User-Agent", "NovaPlayer v0.2")
        c.setRequestProperty("Accept", "application/json")
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code >= 400) error("HTTP $code ${text.take(120)}")
        return text
    }

    suspend fun search(query: String, lang: String): List<Result> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val arr = JSONObject(call("/subtitles?query=$q&languages=$lang&order_by=download_count")).getJSONArray("data")
        (0 until arr.length()).mapNotNull { i ->
            val a = arr.getJSONObject(i).getJSONObject("attributes")
            val f = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            Result(f.getLong("file_id"), a.optString("release", f.optString("file_name", "subtitle")), a.optString("language"), a.optInt("download_count"))
        }
    }

    suspend fun download(fileId: Long, dest: File) = withContext(Dispatchers.IO) {
        if (user.isBlank() || pass.isBlank()) error("Add your OpenSubtitles username and password in Settings")
        val token = JSONObject(call("/login", JSONObject().put("username", user).put("password", pass))).getString("token")
        val link = JSONObject(call("/download", JSONObject().put("file_id", fileId), token)).getString("link")
        (URL(link).openConnection() as HttpURLConnection).inputStream.use { i -> dest.outputStream().use { i.copyTo(it) } }
    }
}
