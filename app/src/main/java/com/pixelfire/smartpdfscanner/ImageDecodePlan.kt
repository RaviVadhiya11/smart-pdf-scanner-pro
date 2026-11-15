package com.pixelfire.smartpdfscanner

internal object ImageDecodePlan {
    /** BitmapFactory accepts power-of-two sampling. Compute bounds with ceiling arithmetic. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int, maxPixels: Long = 6_000_000): Int {
        require(width > 0 && height > 0) { "Cannot read this image." }
        require(maxEdge > 0 && maxPixels > 0)
        var sample = 1
        while (true) {
            val w = (width.toLong() + sample - 1) / sample
            val h = (height.toLong() + sample - 1) / sample
            if (w <= maxEdge && h <= maxEdge && w * h <= maxPixels) return sample
            require(sample <= Int.MAX_VALUE / 2) { "Image dimensions are too large." }
            sample *= 2
        }
    }
}
