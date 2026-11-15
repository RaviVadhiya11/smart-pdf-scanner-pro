package com.pixelfire.smartpdfscanner

import org.junit.Assert.*
import org.junit.Test

class ExportResultSessionTest {
    private fun result(uri: String, name: String = "Scan.pdf") = ExportResultItem(uri, name, "application/pdf")

    @Test fun duplicateNotificationsUpdateOneResult() {
        val session = ExportResultSession()
        session.add(result("file:one"))
        session.add(result("file:one", "Renamed.pdf"))
        assertEquals(1, session.items.size)
        assertEquals("Renamed.pdf", session.selected?.fileName)
    }
    @Test fun splitOutputsStayAccessibleWithoutChangingSelectedFile() {
        val session = ExportResultSession()
        session.add(result("file:selected"))
        session.add(result("file:remaining"))
        assertEquals(2, session.items.size)
        assertEquals("file:selected", session.selected?.uri)
        session.select(1)
        session.add(result("file:third"))
        assertEquals("file:remaining", session.selected?.uri)
    }
    @Test fun clearStartsAFreshOperation() {
        val session = ExportResultSession()
        session.add(result("file:one")); session.add(result("file:two")); session.select(1)
        session.clear()
        assertNull(session.selected)
        assertTrue(session.items.isEmpty())
        session.add(result("file:new"))
        assertEquals(0, session.selectedIndex)
        assertEquals("file:new", session.selected?.uri)
    }
    @Test fun rejectsInvalidFileSelection() {
        val session = ExportResultSession()
        assertThrows(IllegalArgumentException::class.java) { session.select(0) }
        session.add(result("file:one"))
        assertThrows(IllegalArgumentException::class.java) { session.select(-1) }
        assertThrows(IllegalArgumentException::class.java) { session.select(1) }
    }
    @Test fun usesReadableFileTypes() {
        assertEquals("PDF document", ExportResultSession.typeLabel("application/pdf"))
        assertEquals("JPEG image", ExportResultSession.typeLabel("image/jpeg"))
        assertEquals("Word document", ExportResultSession.typeLabel("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals("ZIP archive", ExportResultSession.typeLabel("application/zip"))
        assertEquals("Document", ExportResultSession.typeLabel("application/octet-stream"))
    }
}
