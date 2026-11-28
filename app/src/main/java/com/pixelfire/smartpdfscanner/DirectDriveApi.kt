package com.pixelfire.smartpdfscanner

import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** User-initiated Drive REST operations. Never persist OAuth tokens. */
internal object DirectDriveApi {
    const val MAX_BYTES = 128L * 1024 * 1024
    private const val API = "https://www.googleapis.com/drive/v3"
    data class Backup(val id: String, val name: String)
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun owner(account: String): String = MessageDigest.getInstance("SHA-256")
        .digest(account.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun connection(url: String, token: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Accept", "application/json")
        }

    private fun checkResponse(c: HttpURLConnection) {
        val status = c.responseCode
        require(status in 200..299) {
            when (status) {
                401 -> "Drive authorization expired. Connect again."
                403 -> "Drive access denied. Check permissions or Google Cloud API setup."
                429 -> "Drive is busy. Try again later."
                else -> "Drive request failed (HTTP " + status + ")."
            }
        }
    }

    private fun json(c: HttpURLConnection): JSONObject {
        checkResponse(c)
        val out = java.io.ByteArrayOutputStream()
        c.inputStream.use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                require(total <= 1024 * 1024) { "Drive response too large" }
                out.write(buffer, 0, n)
            }
        }
        return JSONObject(out.toString("UTF-8"))
    }

    fun accountEmail(token: String): String {
        val c = connection(API + "/about?fields=" + enc("user(emailAddress)"), token)
        try {
            return json(c).getJSONObject("user").getString("emailAddress").also {
                require(it.isNotBlank()) { "Google account identity unavailable" }
            }
        } finally { c.disconnect() }
    }

    fun upload(token: String, file: File, mime: String, account: String, allowed:()->Boolean):Boolean {
        val length = file.length()
        require(file.isFile && length in 1L..MAX_BYTES)
        val hash=BackupIntegrity.hash(file,allowed)
        val q="trashed = false and appProperties has { key='docnexaOwner' and value='"+owner(account)+"' } and appProperties has { key='docnexaSha256' and value='"+hash+"' }"
        val check=connection(API+"/files?q="+enc(q)+"&fields="+enc("files(id,size)")+"&pageSize=100",token)
        try{
            val existing=json(check).optJSONArray("files")
            if(existing!=null&&(0 until existing.length()).any{existing.getJSONObject(it).optLong("size",-1)==length}){
                require(allowed()){"Account changed"};return false
            }
        }finally{check.disconnect()}
        val boundary = "docnexa_" + UUID.randomUUID().toString()
        val metadata = JSONObject().put("name", file.name).put("mimeType", mime)
            .put("appProperties", JSONObject().put("docnexaOwner", owner(account)).put("docnexaSha256",hash))
        val head = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
            metadata.toString() + "\r\n--" + boundary + "\r\nContent-Type: " + mime + "\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = ("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8)
        val c = connection("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", token, "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary)
            c.setFixedLengthStreamingMode(head.size.toLong() + length + tail.size)
            c.outputStream.use { out ->
                out.write(head)
                val copiedHash=file.inputStream().use{BackupIntegrity.copy(it,out,length,false,allowed)}
                require(copiedHash==hash){"File changed during upload"}
                out.write(tail)
            }
            require(json(c).optString("id").isNotBlank()) { "Drive did not return a file ID" }
            return true
        } finally { c.disconnect() }
    }

    fun list(token: String, account: String): List<Backup> {
        val q="trashed = false and appProperties has { key='docnexaOwner' and value='"+owner(account)+"' }"
        val url=API+"/files?q="+enc(q)+"&fields="+enc("files(id,name),nextPageToken")+"&pageSize=100&orderBy="+enc("createdTime desc")
        val results=mutableListOf<Backup>()
        var next=""
        repeat(20){
            val c=connection(url+(if(next.isBlank())"" else "&pageToken="+enc(next)),token)
            try{
                val response=json(c)
                val files=response.optJSONArray("files")
                if(files!=null)for(i in 0 until files.length()){
                    val row=files.getJSONObject(i);val id=row.optString("id")
                    if(id.matches(Regex("[A-Za-z0-9_-]+")))results.add(Backup(id,row.optString("name")))
                }
                next=response.optString("nextPageToken")
                if(next.isBlank())return results
            }finally{c.disconnect()}
        }
        return results
    }

    fun download(token:String,id:String,account:String,out:OutputStream,allowed:()->Boolean){
        require(id.matches(Regex("[A-Za-z0-9_-]+"))&&allowed())
        val meta=connection(API+"/files/"+id+"?fields="+enc("id,size,trashed,appProperties"),token)
        val info=try{json(meta)}finally{meta.disconnect()}
        val props=info.optJSONObject("appProperties")?:error("Backup owner unavailable")
        require(!info.optBoolean("trashed")&&props.optString("docnexaOwner")==owner(account)){"Backup belongs to a different account"}
        val size=info.optLong("size",-1)
        val c=connection(API+"/files/"+id+"?alt=media",token)
        try{
            checkResponse(c)
            val actual=c.inputStream.use{BackupIntegrity.copy(it,out,if(size>=0)size else c.contentLengthLong,false,allowed)}
            val expected=props.optString("docnexaSha256")
            if(expected.isNotBlank())require(actual==expected){"Backup integrity check failed"}
        }finally{c.disconnect()}
    }
}
