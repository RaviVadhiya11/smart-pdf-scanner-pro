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
