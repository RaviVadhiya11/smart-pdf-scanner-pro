package com.pixelfire.smartpdfscanner

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal object BackupIntegrity {
    const val MAX_BYTES=128L*1024*1024
    fun copy(input:InputStream,output:OutputStream?,expected:Long=-1,requirePdf:Boolean=false,allowed:()->Boolean):String{
        require(expected<=MAX_BYTES){"Backup exceeds 128 MB"}
        val digest=MessageDigest.getInstance("SHA-256")
        val buffer=ByteArray(64*1024)
        val header=ByteArray(5)
        var total=0L
        while(true){
            require(allowed()){"Backup interrupted or account changed"}
            val n=input.read(buffer);if(n<0)break
            if(n==0)continue
            if(total<5){val count=minOf(n,5-total.toInt());System.arraycopy(buffer,0,header,total.toInt(),count)}
            total+=n;require(total<=MAX_BYTES){"Backup exceeds 128 MB"}
            digest.update(buffer,0,n);output?.write(buffer,0,n)
        }
        require(allowed()&&total>0&&(expected<0||total==expected)){"Incomplete backup transfer"}
        if(requirePdf)require(header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))){"Invalid PDF header"}
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun hash(file:File,allowed:()->Boolean):String{
        require(file.isFile&&file.length() in 1..MAX_BYTES)
        return file.inputStream().use{copy(it,null,file.length(),false,allowed)}
    }
    fun conflictName(name:String,hash:String):String{
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return name.substringBeforeLast('.').take(65)+"_cloud_"+hash.take(20)+".pdf"
    }
}
