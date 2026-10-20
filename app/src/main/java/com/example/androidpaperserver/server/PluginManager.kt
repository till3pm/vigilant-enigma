package com.example.androidpaperserver.server

import android.content.Context
import com.example.androidpaperserver.model.ModrinthVersion
import com.example.androidpaperserver.util.SafeFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PluginManager(private val context: Context, private val serverRoot: File) {
    private val metaFile get() = File(serverRoot, "plugins/.manager.json")

    data class Record(val projectId: String, val fileName: String, val versionId: String, val versionNumber: String)

    fun records(): List<Record> {
        if (!metaFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(metaFile.readText())
            (0 until arr.length()).map { val o = arr.getJSONObject(it); Record(o.getString("projectId"), o.getString("fileName"), o.getString("versionId"), o.getString("versionNumber")) }
        }.getOrDefault(emptyList())
    }

    fun record(projectId: String, version: ModrinthVersion) {
        val updated = records().filterNot { it.projectId == projectId } + Record(projectId, version.fileName, version.id, version.versionNumber)
        val arr = JSONArray(); updated.forEach { arr.put(JSONObject().apply { put("projectId", it.projectId); put("fileName", it.fileName); put("versionId", it.versionId); put("versionNumber", it.versionNumber) }) }
        metaFile.parentFile?.mkdirs(); metaFile.writeText(arr.toString())
    }

    fun remove(record: Record) {
        SafeFiles.child(File(serverRoot, "plugins"), record.fileName).delete()
        val arr = JSONArray(); records().filterNot { it.projectId == record.projectId }.forEach { arr.put(JSONObject().apply { put("projectId", it.projectId); put("fileName", it.fileName); put("versionId", it.versionId); put("versionNumber", it.versionNumber) }) }
        metaFile.writeText(arr.toString())
    }
}
