package com.example.androidpaperserver

import com.example.androidpaperserver.network.PaperApi
import org.junit.Assert.assertEquals
import org.junit.Test

class PaperApiTest {
    @Test fun javaRequirementsMatchPaperMajorLines() {
        assertEquals(21, PaperApi.javaMajorFor("1.21.11"))
        assertEquals(17, PaperApi.javaMajorFor("1.19.4"))
        assertEquals(25, PaperApi.javaMajorFor("26.1"))
    }
}
