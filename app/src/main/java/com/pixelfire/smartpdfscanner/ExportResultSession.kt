package com.pixelfire.smartpdfscanner

internal data class ExportResultItem(val uri: String, val fileName: String, val mime: String)

/** One result surface per operation, with distinct files rather than stacked dialogs. */
internal class ExportResultSession {
    private val entries = mutableListOf<ExportResultItem>()
    val items: List<ExportResultItem> get() = entries.toList()
    var selectedIndex: Int = 0
        private set
    val selected: ExportResultItem? get() = entries.getOrNull(selectedIndex)

    fun add(item: ExportResultItem) {
        val existing = entries.indexOfFirst { it.uri == item.uri }
        if (existing >= 0) entries[existing] = item else entries.add(item)
    }

    fun select(index: Int) {
        require(index in entries.indices)
        selectedIndex = index
    }

    fun clear() {
        entries.clear()
        selectedIndex = 0
    }

    companion object {
        fun typeLabel(mime: String): String = when (mime) {
            "application/pdf" -> "PDF document"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "Word document"
            "application/zip" -> "ZIP archive"
            "image/jpeg" -> "JPEG image"
            "image/png" -> "PNG image"
            else -> if (mime.startsWith("image/")) "Image" else "Document"
        }
    }
}
