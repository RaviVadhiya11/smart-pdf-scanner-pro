package com.pixelfire.smartpdfscanner

import kotlin.math.floor
import kotlin.math.sqrt

/** Bounded raster dimensions; PDF page sizes are in points (72 points/inch). */
internal object NativeExportPlan {
    data class Dimensions(val width: Int, val height: Int, val limited: Boolean)
    fun pdfDimensions(width: Int, height: Int, dpi: Int, maxEdge: Int, maxPixels: Long): Dimensions {
        require(width > 0 && height > 0 && dpi in 1..1200 && maxEdge > 0 && maxPixels > 0)
        val wanted = dpi / 72.0
        val scale = minOf(wanted, maxEdge.toDouble() / maxOf(width, height),
            sqrt(maxPixels.toDouble() / (width.toDouble() * height)))
        var w = floor(width * scale).toInt().coerceIn(1, maxEdge)
        var h = floor(height * scale).toInt().coerceIn(1, maxEdge)
        if (w.toLong() * h > maxPixels) {
            if (w >= h) w = (maxPixels / h).coerceAtLeast(1).toInt()
            else h = (maxPixels / w).coerceAtLeast(1).toInt()
        }
        return Dimensions(w, h, scale < wanted)
    }
}
