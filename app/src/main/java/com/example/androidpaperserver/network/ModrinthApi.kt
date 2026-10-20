package com.example.androidpaperserver.network

import com.example.androidpaperserver.model.ModrinthProject
import com.example.androidpaperserver.model.ModrinthVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class ModrinthApi {
    private val base = "https://api.modrinth.com/v2"

    suspend fun search(query: String, minecraftVersion: String, offset: Int = 0): List<ModrinthProject> = withContext(Dispatchers.IO) {
        val facets = "[[\"project_type:plugin\"],[\"categories:paper\"],[\"versions:$minecraftVersion\"],[\"server_side:required\"]]"
        val url = "$base/search?query=${URLEncoder.encode(query, "UTF-8")}&facets=${URLEncoder.encode(facets, "UTF-8")}&offset=$offset&limit=20"
        val obj = JSONObject(get(url)); val hits = obj.getJSONArray("hits")
        (0 until hits.length()).map { i ->
            val p = hits.getJSONObject(i)
            ModrinthProject(p.getString("project_id"), p.getString("slug"), p.getString("title"), p.optString("description"), p.optString("icon_url").ifBlank { null }, p.optLong("downloads"), jsonStrings(p.optJSONArray("versions")))
        }
    }

    suspend fun versions(projectId: String): List<ModrinthVersion> = withContext(Dispatchers.IO) {
        val arr = JSONArray(get("$base/project/$projectId/version"))
        (0 until arr.length()).mapNotNull { i ->
            val v = arr.getJSONObject(i)
            val files = v.getJSONArray("files")
            val primary = (0 until files.length()).map { files.getJSONObject(it) }.firstOrNull { it.optBoolean("primary", false) }
                ?: files.optJSONObject(0) ?: return@mapNotNull null
            ModrinthVersion(
                v.getString("id"), v.getString("name"), v.getString("version_number"), jsonStrings(v.optJSONArray("game_versions")),
                jsonStrings(v.optJSONArray("loaders")), primary.getString("url"), primary.getString("filename"),
                (0 until v.optJSONArray("dependencies")?.length().orZero()).mapNotNull { j -> v.getJSONArray("dependencies").getJSONObject(j).optString("project_id").ifBlank { null } },
                primary.optJSONObject("hashes")?.optString("sha512")?.ifBlank { null }
            )
        }
    }

    suspend fun download(url: String, destination: File, expectedSha512: String? = null, onProgress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val part = File(destination.parentFile, destination.name + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 60_000; conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "AndroidPaperServer/1.0")
        conn.connect()
        if (conn.responseCode !in 200..299) error("Download failed: HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        conn.inputStream.use { input -> part.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024); var done = 0L; var n: Int
            while (input.read(buffer).also { n = it } >= 0) { if (n == 0) continue; out.write(buffer, 0, n); done += n; onProgress(done, total) }
        }}
        require(part.length() > 0) { "Downloaded plugin is empty" }
        if (expectedSha512 != null) require(hash(part, "SHA-512") == expectedSha512.lowercase()) { "Plugin checksum does not match Modrinth metadata" }
        if (destination.exists()) destination.delete()
        require(part.renameTo(destination)) { "Could not finalize plugin download" }
    }

    private fun hash(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm); file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); var n: Int; while (input.read(buffer).also { n = it } >= 0) { if (n > 0) digest.update(buffer, 0, n) } }; return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000; c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "AndroidPaperServer/1.0")
        c.connect(); if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun jsonStrings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
    private fun Int?.orZero() = this ?: 0
}
