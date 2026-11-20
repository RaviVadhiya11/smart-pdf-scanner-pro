package com.pixelfire.smartpdfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrintPagePlanTest {
    @Test fun allPagesRangeSelectsWholeDocument() {
        assertEquals(
            listOf(0, 1, 2, 3),
            PrintPagePlan.selectedPages(4, listOf(PrintPagePlan.Range(0, Int.MAX_VALUE)))
        )
    }

    @Test fun requestedRangesAreMergedAndSortedWithoutDuplicates() {
        assertEquals(
            listOf(0, 1, 2, 4),
            PrintPagePlan.selectedPages(
                6,
                listOf(
                    PrintPagePlan.Range(4, 4),
                    PrintPagePlan.Range(0, 2),
                    PrintPagePlan.Range(2, 2)
                )
            )
        )
    }

    @Test fun outOfBoundsRangesAreSafelyClippedOrIgnored() {
        assertEquals(
            listOf(3, 4),
            PrintPagePlan.selectedPages(
                5,
                listOf(PrintPagePlan.Range(3, 99), PrintPagePlan.Range(8, 10))
            )
        )
    }

    @Test fun emptyDocumentOrNoRangesPrintsNothing() {
        assertEquals(emptyList<Int>(), PrintPagePlan.selectedPages(0, listOf(PrintPagePlan.Range(0, 3))))
        assertEquals(emptyList<Int>(), PrintPagePlan.selectedPages(5, emptyList()))
    }

    @Test fun negativePageCountIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PrintPagePlan.selectedPages(-1, listOf(PrintPagePlan.Range(0, 1)))
        }
    }
}
