package com.pixelfire.smartpdfscanner

import org.junit.Assert.*
import org.junit.Test

class DocumentViewerPlanTest {
    @Test fun modernOfficeIsExplicitlyTextPreview() {
        assertEquals(DocumentViewerPlan.Kind.OFFICE,DocumentViewerPlan.kind("file.DOCX",DocumentViewerPlan.mime("file.DOCX")!!))
        assertEquals("xlsx",DocumentViewerPlan.officeExtension("download","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    }
    @Test fun legacyFormatsDoNotPretendToRender() {
        for(name in listOf("x.doc","x.xls","x.ppt","x.rtf"))assertEquals(DocumentViewerPlan.Kind.EXTERNAL,DocumentViewerPlan.kind(name,DocumentViewerPlan.mime(name)!!))
    }
    @Test fun routesPdfImagesTextAndArchive() {
        for((name,kind) in listOf("a.pdf" to DocumentViewerPlan.Kind.PDF,"a.png" to DocumentViewerPlan.Kind.IMAGE,"a.csv" to DocumentViewerPlan.Kind.TEXT,"a.html" to DocumentViewerPlan.Kind.TEXT,"a.zip" to DocumentViewerPlan.Kind.ZIP))
            assertEquals(kind,DocumentViewerPlan.kind(name,DocumentViewerPlan.mime(name)!!))
        assertEquals(DocumentViewerPlan.Kind.EXTERNAL,DocumentViewerPlan.kind("x.abc","application/octet-stream"))
    }
    @Test fun decodesUtf16Bom() {
        val bytes=byteArrayOf(0xFF.toByte(),0xFE.toByte())+"hello".toByteArray(Charsets.UTF_16LE)
        assertEquals("hello",DocumentViewerPlan.decodeText(bytes))
    }
}
