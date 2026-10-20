package com.example.androidpaperserver

import com.example.androidpaperserver.util.SafeFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafeFilesTest {
    @Test fun acceptsSimpleChild() {
        val root = createTempDir()
        assertTrue(SafeFiles.child(root, "paper.jar").path.startsWith(root.canonicalPath))
        root.deleteRecursively()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversal() {
        SafeFiles.child(createTempDir(), "../escape")
    }
}
