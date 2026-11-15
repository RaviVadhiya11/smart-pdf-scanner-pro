package com.pixelfire.smartpdfscanner

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class PrivateDocumentStoreTest {
    private fun inFolder(test: (File) -> Unit) {
        val folder = Files.createTempDirectory("docnexa-test-").toFile()
        try { test(folder) } finally { folder.deleteRecursively() }
    }
    @Test fun repeatedNamesNeverOverwritePreviousBytes() = inFolder { folder ->
        val first = PrivateDocumentStore.save(folder, "first".toByteArray(), "Scan.pdf")
        val second = PrivateDocumentStore.save(folder, "second".toByteArray(), "Scan.pdf")
        assertEquals("Scan.pdf", first.name)
        assertEquals("Scan_2.pdf", second.name)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
    }
    @Test fun failedWritesDoNotPublishPartialFiles() = inFolder { folder ->
        val original = PrivateDocumentStore.save(folder, "original".toByteArray(), "Scan.pdf")
        assertThrows(IOException::class.java) {
            PrivateDocumentStore.save(folder, "Scan.pdf") { it.write("partial".toByteArray()); throw IOException("disk full") }
        }
        assertEquals("original", original.readText())
        assertEquals(listOf("Scan.pdf"), folder.listFiles()!!.map { it.name })
    }
    @Test fun concurrentSavesGetDistinctNamesAndCorrectContents() = inFolder { folder ->
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..16).map { number -> executor.submit<File> { PrivateDocumentStore.save(folder, "$number".toByteArray(), "Scan.pdf") } }
            val results = jobs.map { it.get() }
            assertEquals(16, results.map { it.name }.distinct().size)
            assertEquals((1..16).map { "$it" }.toSet(), results.map { it.readText() }.toSet())
            assertTrue(folder.listFiles()!!.none { it.extension == "pending" })
        } finally { executor.shutdownNow() }
    }
    @Test fun longNamesPreserveExtensionsAndStayInsideTheFolder() = inFolder { folder ->
        val file = PrivateDocumentStore.save(folder, byteArrayOf(1), "../" + "long".repeat(80) + ".pdf")
        assertEquals(folder.canonicalFile, file.parentFile.canonicalFile)
        assertEquals("pdf", file.extension)
        assertTrue(file.name.length <= 100)
        assertFalse(file.name.startsWith("."))
    }
    @Test fun unavailableDestinationFailsWithoutChangingExistingData() = inFolder { folder ->
        val blocked = File(folder, "not-a-folder").apply { writeText("keep") }
        assertThrows(IOException::class.java) { PrivateDocumentStore.save(blocked, byteArrayOf(1), "Scan.pdf") }
        assertEquals("keep", blocked.readText())
    }
}
