package com.example.androidpaperserver.data

import android.content.Context
import com.example.androidpaperserver.model.ServerInstance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ServerRepository(private val context: Context) {
    private val registryFile get() = File(context.filesDir, "servers.json")
    val rootDirectory: File get() = File(context.filesDir, "servers").also { it.mkdirs() }

    @Synchronized fun list(): List<ServerInstance> {
        if (!registryFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(registryFile.readText())
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun get(id: String): ServerInstance? = list().firstOrNull { it.id == id }

    @Synchronized fun save(server: ServerInstance) {
        val updated = list().filterNot { it.id == server.id } + server
        val array = JSONArray()
        updated.forEach { array.put(toJson(it)) }
        registryFile.writeText(array.toString())
        directory(server.id).mkdirs()
    }

    @Synchronized fun delete(server: ServerInstance) {
        val updated = list().filterNot { it.id == server.id }
        val array = JSONArray()
        updated.forEach { array.put(toJson(it)) }
        registryFile.writeText(array.toString())
        directory(server.id).deleteRecursively()
    }

    fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid server id" }
        return File(rootDirectory, id).canonicalFile.also {
            require(it.path.startsWith(rootDirectory.canonicalPath + File.separator))
        }
    }

    private fun toJson(s: ServerInstance) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("minecraftVersion", s.minecraftVersion)
        put("paperBuild", s.paperBuild); put("minMemoryMb", s.minMemoryMb); put("maxMemoryMb", s.maxMemoryMb)
        put("port", s.port); put("maxPlayers", s.maxPlayers); put("viewDistance", s.viewDistance)
        put("simulationDistance", s.simulationDistance); put("motd", s.motd); put("eulaAccepted", s.eulaAccepted)
        put("createdAt", s.createdAt)
    }

    private fun fromJson(o: JSONObject) = ServerInstance(
        o.getString("id"), o.getString("name"), o.getString("minecraftVersion"), o.getInt("paperBuild"),
        o.getInt("minMemoryMb"), o.getInt("maxMemoryMb"), o.getInt("port"), o.getInt("maxPlayers"),
        o.getInt("viewDistance"), o.getInt("simulationDistance"), o.getString("motd"),
        o.optBoolean("eulaAccepted", false), o.getLong("createdAt")
    )
}
