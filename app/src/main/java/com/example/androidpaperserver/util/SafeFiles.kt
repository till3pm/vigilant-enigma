package com.example.androidpaperserver.util

import java.io.File

object SafeFiles {
    fun child(root: File, name: String): File {
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != "." && name != "..")
        val target = File(root, name).canonicalFile
        require(target.path.startsWith(root.canonicalFile.path + File.separator))
        return target
    }
}
