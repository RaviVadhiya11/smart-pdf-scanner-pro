package com.pixelfire.smartpdfscanner

internal object PrintPagePlan {
    data class Range(val start: Int, val end: Int)

    fun selectedPages(pageCount: Int, ranges: List<Range>): List<Int> {
        require(pageCount >= 0) { "Page count cannot be negative." }
        if (pageCount == 0 || ranges.isEmpty()) return emptyList()

        val selected = linkedSetOf<Int>()
        for (range in ranges) {
            val start = range.start.coerceAtLeast(0)
            val end = if (range.end == Int.MAX_VALUE) pageCount - 1 else range.end.coerceAtMost(pageCount - 1)
            if (start > end || start >= pageCount) continue
            for (page in start..end) selected.add(page)
        }
        return selected.toList().sorted()
    }
}
