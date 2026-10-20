package com.example.androidpaperserver.model

data class ServerInstance(
    val id: String,
    val name: String,
    val minecraftVersion: String,
    val paperBuild: Int,
    val minMemoryMb: Int,
    val maxMemoryMb: Int,
    val port: Int,
    val maxPlayers: Int,
    val viewDistance: Int,
    val simulationDistance: Int,
    val motd: String,
    val eulaAccepted: Boolean,
    val createdAt: Long
)

enum class ServerState { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }

data class PaperBuild(val version: String, val build: Int, val javaMajor: Int, val downloadUrl: String, val sha256: String?, val size: Long?)

data class ModrinthProject(
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val downloads: Long,
    val versions: List<String>
)

data class ModrinthVersion(
    val id: String,
    val name: String,
    val versionNumber: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val fileUrl: String,
    val fileName: String,
    val dependencies: List<String>,
    val sha512: String?
)
