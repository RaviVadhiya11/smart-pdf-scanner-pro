package com.pixelfire.smartpdfscanner

/** Pure page-selection logic; page indices returned here are zero based. */
internal object PdfPagePlan {
    enum class Operation(val title: String, val outputPrefix: String) {
        REORDER("Reorder Pages", "Reordered"),
        DELETE("Delete Pages", "Pages_Removed"),
        EXTRACT("Extract Pages", "Extracted"),
        DUPLICATE("Duplicate Pages", "Duplicated")
    }

    fun selection(value: String, total: Int): List<Int> {
        require(total > 0) { "This PDF has no pages." }
        require(value.isNotBlank()) { "Enter page numbers or ranges." }
        require(value.length <= 50000) { "The page list is too long." }
        // Advanced quick selections for PDF editing: all, odd and even pages.
        // These are 1-based page numbers, converted to zero-based indices.
        when (value.trim().lowercase(java.util.Locale.ROOT)) {
            "all" -> return (0 until total).toList()
            "odd" -> return (0 until total step 2).toList()
            "even" -> return (1 until total step 2).toList().also {
                require(it.isNotEmpty()) { "This PDF has no even-numbered pages." }
            }
        }
        val selected = ArrayList<Int>()
        val seen = HashSet<Int>()
        for (part in value.split(',')) {
            val match = Regex("^\\s*(\\d+)\\s*(?:-\\s*(\\d+)\\s*)?$").matchEntire(part)
                ?: throw IllegalArgumentException("Use page numbers and ranges, for example 1-3, 5.")
            val first = match.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid page number.")
            val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?: if (match.groupValues[2].isEmpty()) first else throw IllegalArgumentException("Invalid page number.")
            require(first in 1..total && last in 1..total) { "Pages must be between 1 and $total." }
            val range = if (first <= last) first..last else first downTo last
            for (page in range) {
                require(seen.add(page - 1)) { "Page $page appears more than once. Enter each page once." }
                selected.add(page - 1)
            }
        }
        return selected
    }

    /** Compact consecutive runs without losing a user's chosen page order. */
    fun formatSelection(pages: List<Int>): String {
        if (pages.isEmpty()) return ""
        val parts = ArrayList<String>()
        var index = 0
        while (index < pages.size) {
            val first = pages[index]
            var last = index
            val step = if (index + 1 < pages.size) pages[index + 1] - first else 0
            if (step == 1 || step == -1) {
                while (last + 1 < pages.size && pages[last + 1] - pages[last] == step) last++
            }
            parts.add(if (last == index) "${first + 1}" else "${first + 1}-${pages[last] + 1}")
            index = last + 1
        }
        return parts.joinToString(", ")
    }

    fun move(pages: List<Int>, page: Int, direction: Int): List<Int> {
        require(direction == -1 || direction == 1) { "Choose an adjacent position." }
        val from = pages.indexOf(page)
        val to = from + direction
        if (from == -1 || to !in pages.indices) return pages.toList()
        return pages.toMutableList().apply { java.util.Collections.swap(this, from, to) }
    }

    fun build(operation: Operation, value: String, total: Int): List<Int> {
        val selected = selection(value, total)
        return when (operation) {
            Operation.REORDER -> {
                require(selected.size == total) { "Include all $total pages exactly once. Use Extract Pages to keep fewer pages." }
                selected
            }
            Operation.EXTRACT -> selected
            Operation.DELETE -> {
                require(selected.size < total) { "Keep at least one page in the PDF." }
                val removed = selected.toHashSet()
                (0 until total).filterNot { it in removed }
            }
            Operation.DUPLICATE -> {
                val duplicated = selected.toHashSet()
                (0 until total).flatMap { if (it in duplicated) listOf(it, it) else listOf(it) }
            }
        }
    }
}
