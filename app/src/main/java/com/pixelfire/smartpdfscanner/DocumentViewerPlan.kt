package com.pixelfire.smartpdfscanner

import java.util.Locale

internal object DocumentViewerPlan {
    enum class Kind { PDF, IMAGE, OFFICE, TEXT, ZIP, EXTERNAL }
    private fun ext(name:String)=name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun mime(name:String):String?=when(ext(name)){
        "pdf"->"application/pdf"
        "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx"->"application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "doc"->"application/msword"
        "xls"->"application/vnd.ms-excel"
        "ppt"->"application/vnd.ms-powerpoint"
        "jpg","jpeg"->"image/jpeg"
        "png"->"image/png"
        "webp"->"image/webp"
        "bmp"->"image/bmp"
        "gif"->"image/gif"
        "heic","heif"->"image/heif"
        "txt","log","md"->"text/plain"
        "csv"->"text/csv"
        "tsv"->"text/tab-separated-values"
        "html","htm"->"text/html"
        "xml"->"text/xml"
        "json"->"application/json"
        "rtf"->"application/rtf"
        "zip"->"application/zip"
        else->null
    }
    fun officeExtension(name:String,mime:String):String=when {
        ext(name) in setOf("docx","xlsx","pptx")->ext(name)
        mime==this.mime("a.docx")->"docx"
        mime==this.mime("a.xlsx")->"xlsx"
        mime==this.mime("a.pptx")->"pptx"
        else->""
    }
    fun kind(name:String,mime:String):Kind=when{
        ext(name) in setOf("doc","xls","ppt","rtf")->Kind.EXTERNAL
        mime=="application/pdf"->Kind.PDF
        mime.startsWith("image/") && mime!="image/svg+xml"->Kind.IMAGE
        officeExtension(name,mime).isNotEmpty()->Kind.OFFICE
        mime=="application/zip"||mime=="application/x-zip-compressed"->Kind.ZIP
        (mime.startsWith("text/")&&mime!="text/rtf")||mime=="application/json"||mime=="application/xml"->Kind.TEXT
        else->Kind.EXTERNAL
    }
    fun decodeText(bytes:ByteArray):String=when{
        bytes.size>=2 && bytes[0]==0xFF.toByte()&&bytes[1]==0xFE.toByte()->String(bytes,2,bytes.size-2,Charsets.UTF_16LE)
        bytes.size>=2 && bytes[0]==0xFE.toByte()&&bytes[1]==0xFF.toByte()->String(bytes,2,bytes.size-2,Charsets.UTF_16BE)
        bytes.size>=3 && bytes[0]==0xEF.toByte()&&bytes[1]==0xBB.toByte()&&bytes[2]==0xBF.toByte()->String(bytes,3,bytes.size-3,Charsets.UTF_8)
        else->String(bytes,Charsets.UTF_8)
    }
}
