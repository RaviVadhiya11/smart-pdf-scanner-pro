package com.pixelfire.smartpdfscanner

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Publish a complete file under a new name; never truncate an existing document. */
internal object PrivateDocumentStore {
    fun save(directory: File, bytes: ByteArray, name: String): File = save(directory, name) { it.write(bytes) }

    @Synchronized
    fun save(directory: File, name: String, writeContents: (OutputStream) -> Unit): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the documents folder.")
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').ifBlank { "Document" }
        val extension = clean.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
            ?.let { ".$it" }.orEmpty()
        val base = (if (extension.isEmpty()) clean else clean.dropLast(extension.length)).ifBlank { "Document" }
        val pending = File.createTempFile("docnexa_", ".pending", directory)
        var reserved: File? = null
        var published = false
        try {
            FileOutputStream(pending).use { output ->
                writeContents(output)
                output.flush()
                output.fd.sync()
            }
            for (index in 0 until 10000) {
                val suffix = if (index == 0) "" else "_${index + 1}"
                val candidate = File(directory, base.take(100 - extension.length - suffix.length) + suffix + extension)
                if (candidate.createNewFile()) { reserved = candidate; break }
            }
            val target = reserved ?: throw IOException("Too many documents share this file name. Choose another name.")
            if (!pending.renameTo(target)) throw IOException("Could not finish saving this document.")
            published = true
            return target
        } finally {
            pending.delete()
            if (!published) reserved?.delete()
        }
    }
}
