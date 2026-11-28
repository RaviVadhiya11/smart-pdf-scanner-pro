package com.pixelfire.smartpdfscanner

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupIntegrityTest {
    private val pdf="%PDF-1.7\nTest document".toByteArray(Charsets.US_ASCII)

    @Test fun copiesBytesAndVerifiesSha256(){
        val out=ByteArrayOutputStream()
        val actual=BackupIntegrity.copy(ByteArrayInputStream(pdf),out,pdf.size.toLong(),true){true}
        assertArrayEquals(pdf,out.toByteArray())
        val expected=java.security.MessageDigest.getInstance("SHA-256").digest(pdf)
            .joinToString(""){"%02x".format(it.toInt() and 255)}
        assertEquals(expected,actual)
    }
    @Test(expected=IllegalArgumentException::class)
    fun rejectsTruncatedTransfer(){
        BackupIntegrity.copy(ByteArrayInputStream(pdf),null,pdf.size.toLong()+1,true){true}
    }
    @Test(expected=IllegalArgumentException::class)
    fun rejectsInvalidPdf(){
        BackupIntegrity.copy(ByteArrayInputStream("not a PDF".toByteArray()),null,-1,true){true}
    }
    @Test(expected=IllegalArgumentException::class)
    fun rejectsOversizedDeclaredLength(){
        BackupIntegrity.copy(ByteArrayInputStream(pdf),null,BackupIntegrity.MAX_BYTES+1,true){true}
    }
    @Test(expected=IllegalArgumentException::class)
    fun stopsWhenAccountChanges(){
        var checks=0
        BackupIntegrity.copy(ByteArrayInputStream(pdf),null,-1,true){++checks<2}
    }
    @Test fun conflictAliasMatchesLongTrashedNames(){
        val original="a".repeat(90)+".pdf"
        val alias=BackupIntegrity.conflictName(original,"b".repeat(64))
        assertTrue(alias.length<=100)
        assertTrue(BackupIntegrity.isTrashAlias(original,alias))
        assertFalse(BackupIntegrity.isTrashAlias("other.pdf",alias))
    }
}
