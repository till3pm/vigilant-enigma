package com.example.androidpaperserver.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {
    fun create(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            source.walkTopDown().filter { it.isFile && it.canonicalPath != destination.canonicalPath }.forEach { file ->
                val rel = file.relativeTo(source).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(rel))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun restore(archive: File, destination: File) {
        require(archive.isFile) { "Backup archive does not exist" }
        val parent = destination.parentFile ?: error("Backup destination has no parent")
        val staging = File(parent, ".${destination.name}.restore-${System.currentTimeMillis()}")
        staging.mkdirs()
        try {
            ZipInputStream(archive.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val normalized = entry.name.replace('\\', '/')
                    require(!normalized.startsWith("/") && normalized.split('/').none { it == ".." }) { "Unsafe backup entry" }
                    val target = File(staging, normalized).canonicalFile
                    require(target.path.startsWith(staging.canonicalPath + File.separator)) { "Unsafe backup entry" }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val old = File(parent, ".${destination.name}.restore-old-${System.currentTimeMillis()}")
            if (destination.exists()) {
                require(destination.renameTo(old)) { "Could not stage existing server data" }
            }
            if (!staging.renameTo(destination)) {
                old.renameTo(destination)
                error("Could not activate restored server data")
            }
            old.deleteRecursively()
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }
}
