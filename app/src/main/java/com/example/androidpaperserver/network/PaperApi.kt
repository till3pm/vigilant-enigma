package com.example.androidpaperserver.network

import com.example.androidpaperserver.model.PaperBuild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class PaperApi {
    private val base = "https://fill.papermc.io/v3"
    private val userAgent = "AndroidPaperServer/1.0 (https://github.com/)"

    suspend fun versions(): List<String> = withContext(Dispatchers.IO) {
        val obj = JSONObject(get("$base/projects/paper"))
        val groups = obj.optJSONObject("versions") ?: JSONObject()
        val all = mutableListOf<String>()
        val keys = groups.keys()
        while (keys.hasNext()) { val key = keys.next(); val arr = groups.optJSONArray(key) ?: continue; for (i in 0 until arr.length()) all += arr.getString(i) }
        all.distinct().sortedWith(Comparator { a, b -> compareVersions(b, a) })
    }

    suspend fun latestBuild(version: String): PaperBuild = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(version, "UTF-8")
        val arr = org.json.JSONArray(get("$base/projects/paper/versions/$encoded/builds"))
        val stable = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("channel") == "STABLE" }
            .maxByOrNull { it.optInt("id") } ?: error("No stable Paper build is available for $version")
        val downloads = stable.getJSONObject("downloads").getJSONObject("server:default")
        val checksums = downloads.optJSONObject("checksums")
        PaperBuild(version, stable.getInt("id"), javaMajorFor(version), downloads.getString("url"), checksums?.optString("sha256")?.ifBlank { null }, downloads.optLong("size").takeIf { it > 0 })
    }

    suspend fun download(build: PaperBuild, destination: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        val conn = URL(build.downloadUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000; conn.requestMethod = "GET"; conn.setRequestProperty("User-Agent", userAgent); conn.connect()
        if (conn.responseCode !in 200..299) error("Paper download failed: HTTP ${conn.responseCode}")
        val total = if (build.size != null && build.size > 0) build.size else conn.contentLengthLong
        conn.inputStream.use { input -> part.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024); var done = 0L; var n: Int
            while (input.read(buffer).also { n = it } >= 0) { if (n == 0) continue; out.write(buffer, 0, n); done += n; onProgress(done, total) }
        }}
        require(part.length() > 0) { "Downloaded Paper file is empty" }
        if (build.size != null) require(part.length() == build.size) { "Paper download size does not match metadata" }
        if (build.sha256 != null) require(sha256(part) == build.sha256.lowercase()) { "Paper download checksum does not match metadata" }
        if (destination.exists()) destination.delete(); require(part.renameTo(destination)) { "Could not finalize Paper download" }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000; conn.requestMethod = "GET"; conn.setRequestProperty("User-Agent", userAgent); conn.connect()
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); var n: Int; while (input.read(buffer).also { n = it } >= 0) { if (n > 0) digest.update(buffer, 0, n) } }; return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = a.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }; val bb = b.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(aa.size, bb.size)
        for (i in 0 until n) { val x = aa.getOrElse(i) { 0 }; val y = bb.getOrElse(i) { 0 }; if (x != y) return x.compareTo(y) }
        return a.compareTo(b)
    }

    companion object {
        fun javaMajorFor(version: String): Int {
            if (version.startsWith("26.")) return 25
            val minor = version.removePrefix("1.").substringBefore('.').toIntOrNull() ?: return 21
            return when {
                version.startsWith("1.20") || version.startsWith("1.21") -> 21
                version.startsWith("1.18") || version.startsWith("1.19") -> 17
                version.startsWith("1.17") -> 16
                minor >= 12 -> 11
                minor >= 2 -> 8
                else -> 8
            }
        }
    }
}
