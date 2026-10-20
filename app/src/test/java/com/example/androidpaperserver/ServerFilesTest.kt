package com.example.androidpaperserver

import com.example.androidpaperserver.server.ServerFiles
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ServerFilesTest {
    @Test fun updatePropertyPreservesOtherLines() {
        val file = createTempFile()
        file.writeText("# comment\nserver-port=25565\nmotd=hello\n")
        ServerFiles.updateProperty(file, "server-port", "25566")
        val props = ServerFiles.readProperties(file)
        assertEquals("25566", props["server-port"])
        assertEquals("hello", props["motd"])
        file.delete()
    }
}
