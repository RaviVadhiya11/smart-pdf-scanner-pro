package com.pixelfire.smartpdfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfPagePlanTest {
    @Test fun extractPreservesRequestedOrderAndReverseRanges() {
        assertEquals(listOf(4, 2, 1, 0), PdfPagePlan.build(PdfPagePlan.Operation.EXTRACT, "5, 3-1", 5))
    }
    @Test fun reorderRequiresEveryPageExactlyOnce() {
        assertEquals(listOf(2, 0, 1), PdfPagePlan.build(PdfPagePlan.Operation.REORDER, "3,1-2", 3))
        assertThrows(IllegalArgumentException::class.java) { PdfPagePlan.build(PdfPagePlan.Operation.REORDER, "2,1", 3) }
    }
    @Test fun deletingPagesKeepsOriginalOrder() {
        assertEquals(listOf(0, 3, 4), PdfPagePlan.build(PdfPagePlan.Operation.DELETE, "2-3", 5))
    }
    @Test fun cannotDeleteEveryPageIncludingSinglePageFiles() {
        assertThrows(IllegalArgumentException::class.java) { PdfPagePlan.build(PdfPagePlan.Operation.DELETE, "1-3", 3) }
        assertThrows(IllegalArgumentException::class.java) { PdfPagePlan.build(PdfPagePlan.Operation.DELETE, "1", 1) }
    }
    @Test fun duplicatesAreInsertedImmediatelyAfterOriginals() {
        assertEquals(listOf(0, 0, 1, 2, 2), PdfPagePlan.build(PdfPagePlan.Operation.DUPLICATE, "3,1", 3))
    }
    @Test fun rejectsOverlappingRangesAndDuplicateNumbers() {
        listOf("1-3,3", "2,2", "1-2,2-1").forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { PdfPagePlan.selection(value, 4) }
        }
    }
    @Test fun rejectsEmptyMalformedAndOutOfBoundsSelections() {
        listOf("", " ", "1,", ",1", "1,,2", "0", "-1", "4", "1-4", "a", "1-2-3", "2147483648", "1-2147483648").forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { PdfPagePlan.selection(value, 3) }
        }
        assertThrows(IllegalArgumentException::class.java) { PdfPagePlan.selection("1", 0) }
    }
    @Test fun allowsWhitespaceAndSinglePageExtraction() {
        assertEquals(listOf(0), PdfPagePlan.selection(" 1 ", 1))
        assertEquals(listOf(0, 1, 3), PdfPagePlan.selection(" 1 - 2 , 4 ", 4))
    }
    @Test fun compactSelectionsRoundTripWithoutChangingOrder() {
        listOf(listOf(0), listOf(0, 1, 2, 4), listOf(5, 4, 3, 0, 1), (0 until 20000).toList()).forEach { pages ->
            assertEquals(pages, PdfPagePlan.selection(PdfPagePlan.formatSelection(pages), pages.max() + 1))
        }
        assertEquals("", PdfPagePlan.formatSelection(emptyList()))
    }
    @Test fun arrowMovesPreserveAllPagesAndRespectBoundaries() {
        assertEquals(listOf(1, 0, 2), PdfPagePlan.move(listOf(0, 1, 2), 0, 1))
        assertEquals(listOf(2, 0, 1), PdfPagePlan.move(listOf(0, 2, 1), 2, -1))
        assertEquals(listOf(0, 1), PdfPagePlan.move(listOf(0, 1), 0, -1))
        assertEquals(listOf(0, 1), PdfPagePlan.move(listOf(0, 1), 1, 1))
        assertEquals(listOf(0, 1), PdfPagePlan.move(listOf(0, 1), 4, -1))
    }
}
