package com.pixelfire.smartpdfscanner

import org.junit.Assert.*
import org.junit.Test

class ImageDecodePlanTest {
    @Test fun smallImagesRetainTheirResolution() { assertEquals(1, ImageDecodePlan.sampleSize(800, 600, 2400)) }
    @Test fun largePhotosStayWithinMemoryAndEdgeBounds() {
        listOf(8000 to 6000, 6000 to 8000, 100000 to 100, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (width, height) ->
            val sample = ImageDecodePlan.sampleSize(width, height, 2400)
            val w = (width.toLong() + sample - 1) / sample
            val h = (height.toLong() + sample - 1) / sample
            assertTrue(w <= 2400 && h <= 2400 && w * h <= 6_000_000)
            assertEquals(0, sample and (sample - 1))
        }
    }
    @Test fun oddDimensionsUseCeilingBounds() { assertEquals(4, ImageDecodePlan.sampleSize(4001, 3001, 2000)) }
    @Test fun pixelBudgetCanForceAdditionalSampling() { assertEquals(2, ImageDecodePlan.sampleSize(2400, 2400, 2400, 2_000_000)) }
    @Test fun rejectsUnreadableImagesAndInvalidLimits() {
        assertThrows(IllegalArgumentException::class.java) { ImageDecodePlan.sampleSize(-1, 10, 2400) }
        assertThrows(IllegalArgumentException::class.java) { ImageDecodePlan.sampleSize(10, 0, 2400) }
        assertThrows(IllegalArgumentException::class.java) { ImageDecodePlan.sampleSize(10, 10, 0) }
    }
}
