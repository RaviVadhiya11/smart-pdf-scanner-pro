package com.pixelfire.smartpdfscanner

import org.junit.Assert.*
import org.junit.Test

class NativeExportPlanTest {
    @Test fun a4At300Dpi() {
        val d = NativeExportPlan.pdfDimensions(595, 842, 300, 7680, 16_000_000)
        assertEquals(2479, d.width)
        assertEquals(3508, d.height)
        assertFalse(d.limited)
    }
    @Test fun largePageUsesSafePixelBudget() {
        val d = NativeExportPlan.pdfDimensions(595, 842, 600, 7680, 16_000_000)
        assertTrue(d.limited)
        assertTrue(d.width.toLong() * d.height <= 16_000_000)
        assertEquals(595.0 / 842, d.width.toDouble() / d.height, 0.001)
    }
    @Test fun landscapeAndVeryThinPagesAreBounded() {
        for ((w,h) in listOf(842 to 595, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            val d = NativeExportPlan.pdfDimensions(w,h,600,7680,1000)
            assertTrue(d.width in 1..7680 && d.height in 1..7680)
            assertTrue(d.width.toLong()*d.height <= 1000)
        }
    }
    @Test fun invalidPageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NativeExportPlan.pdfDimensions(0, 842, 300, 7680, 16_000_000) }
    }
}
