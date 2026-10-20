package com.example.androidpaperserver.server

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

class RuntimeManager(private val context: Context) {
    val runtimeRoot: File get() = File(context.filesDir, "runtime").also { it.mkdirs() }

    fun javaExecutable(): File? {
        val candidates = listOf("bin/java", "bin/java.exe")
        return candidates.map { File(runtimeRoot, it) }.firstOrNull { it.isFile && it.canExecute() }
            ?: candidates.map { File(runtimeRoot, it) }.firstOrNull { it.isFile }
    }

    fun javaMajor(): Int? {
        val java = javaExecutable() ?: return null
        return runCatching {
            val p = ProcessBuilder(java.absolutePath, "-version").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            Regex("(?:version|openjdk)\\s+\\\"?(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        }.getOrNull()
    }

    fun clear() { runtimeRoot.deleteRecursively(); runtimeRoot.mkdirs() }

    fun importZip(input: java.io.InputStream) {
        clear()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith("/") && !name.split("/").contains("..")) { "Unsafe runtime archive entry" }
                val target = File(runtimeRoot, name).canonicalFile
                require(target.path.startsWith(runtimeRoot.canonicalPath + File.separator)) { "Unsafe runtime archive entry" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    if (target.path.contains("/bin/")) target.setExecutable(true, false)
                }
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        val java = javaExecutable() ?: error("The archive does not contain bin/java")
        require(java.canExecute() || java.setExecutable(true, false)) { "Java executable cannot be executed" }
        require(javaMajor() != null) { "The selected runtime could not be started on this device" }
    }
}
