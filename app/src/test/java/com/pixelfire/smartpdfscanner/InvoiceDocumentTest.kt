package com.pixelfire.smartpdfscanner

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceDocumentTest {
    private fun draft(interstate:Boolean, discount:String="0")=InvoiceDocument.Draft(
        "GST Invoice","T-1","26/11/2026","","Seller","","","","Buyer","","","Gujarat",
        listOf(InvoiceDocument.Item("Service","",BigDecimal("2"),BigDecimal("100"),BigDecimal("18"))),
        BigDecimal(discount),interstate,"",0,null,null
    )
    @Test fun interstateTax() {
        val t=InvoiceDocument.totals(draft(true))
        assertEquals("36.00",InvoiceDocument.format(t.igst))
        assertEquals("236.00",InvoiceDocument.format(t.total))
    }
    @Test fun intrastateDiscount() {
        val t=InvoiceDocument.totals(draft(false,"20"))
        assertEquals("16.20",InvoiceDocument.format(t.cgst))
        assertEquals("16.20",InvoiceDocument.format(t.sgst))
        assertEquals("212.40",InvoiceDocument.format(t.total))
    }
}