package com.pixelfire.smartpdfscanner

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object SupabaseCloud {
    private const val BASE_URL = "https://xayptkukxsmogxsnpnpa.supabase.co"
    // Supabase publishable key. This is intended for client-side use; never put a service_role key in an app.
    private const val API_KEY = "sb_publishable_GYXmh25W433C0ll6i8ldEw_tf37VR2K"
    private const val BUCKET = "docnexa-pdfs"

    data class AuthResult(val accessToken: String, val userId: String, val message: String, val refreshToken: String = "")
    data class OAuthResult(val accessToken: String, val refreshToken: String, val userId: String, val email: String)

    fun buildOAuthUrl(provider: String, redirectUri: String, codeChallenge: String, state: String): String {
        require(provider == "google" || provider == "facebook") { "Unsupported sign-in provider." }
        val query = listOf(
            "provider" to provider,
            "redirect_to" to redirectUri,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "s256",
            "state" to state
        ).joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        return "$BASE_URL/auth/v1/authorize?$query"
    }

    fun exchangeOAuthCode(code: String, verifier: String): OAuthResult {
        val payload = JSONObject()
            .put("auth_code", code)
            .put("code_verifier", verifier)
            .toString().toByteArray()
        val response = request("POST", "/auth/v1/token?grant_type=pkce", payload, "application/json")
        val json = JSONObject(response)
        val user = json.optJSONObject("user") ?: JSONObject()
        val access = json.optString("access_token")
        if (access.isBlank()) throw IllegalStateException("Supabase did not return a sign-in session.")
        return OAuthResult(
            accessToken = access,
            refreshToken = json.optString("refresh_token"),
            userId = user.optString("id"),
            email = user.optString("email")
        )
    }

    fun authenticate(email: String, password: String, signUp: Boolean, fullName: String = ""): AuthResult {
        val endpoint = if (signUp) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
        val payload = JSONObject().put("email", email).put("password", password)
        if (signUp && fullName.isNotBlank()) {
            payload.put("data", JSONObject().put("full_name", fullName))
        }
        val body = payload.toString().toByteArray()
        val response = request("POST", endpoint, body, "application/json")
        val json = JSONObject(response)
        val token = json.optString("access_token")
        val user = json.optJSONObject("user") ?: json
        val userId = user.optString("id")
        if (token.isBlank()) {
            val msg = if (signUp) "Account created. Check your inbox and spam folder for the confirmation link. Confirm your email, then sign in." else
                json.optString("msg").ifBlank { json.optString("message", "Sign-in did not return a session.") }
            return AuthResult("", "", msg)
        }
        if (userId.isBlank()) throw IllegalStateException("Supabase did not return an account ID.")
        return AuthResult(token, userId, "Signed in", json.optString("refresh_token"))
    }

    fun refreshSession(refreshToken: String): OAuthResult {
        require(refreshToken.isNotBlank()) { "No refresh token is available. Please sign in again." }
        val payload = JSONObject().put("refresh_token", refreshToken).toString().toByteArray(Charsets.UTF_8)
        val json = JSONObject(request("POST", "/auth/v1/token?grant_type=refresh_token", payload, "application/json"))
        val user = json.optJSONObject("user") ?: JSONObject()
        val access = json.optString("access_token")
        val nextRefresh = json.optString("refresh_token")
        val userId = user.optString("id")
        if (access.isBlank() || userId.isBlank()) throw IllegalStateException("Could not refresh the DocNexa session. Please sign in again.")
        return OAuthResult(access, nextRefresh, userId, user.optString("email"))
    }


    fun generateAi(token: String, task: String, prompt: String, documentText: String): String {
        require(token.isNotBlank()) { "Please sign in to DocNexa first." }
        val payload = JSONObject()
            .put("task", task)
            .put("prompt", prompt)
            .put("documentText", documentText)
            .toString().toByteArray(Charsets.UTF_8)
        val response = request(
            "POST",
            "/functions/v1/docnexa-ai",
            payload,
            "application/json",
            token
        )
        val json = JSONObject(response)
        if (!json.optBoolean("success", false)) {
            throw IllegalStateException(json.optString("error").ifBlank { "AI request failed." })
        }
        return json.optString("answer").ifBlank { "The AI service returned an empty answer." }
    }

    fun playBilling(token: String, action: String, purchaseToken: String = ""): JSONObject {
        require(token.isNotBlank()) { "Sign in to DocNexa first." }
        val payload = JSONObject().put("action", action).put("purchaseToken", purchaseToken)
        return JSONObject(request("POST", "/functions/v1/docnexa-play-billing",
            payload.toString().toByteArray(Charsets.UTF_8), "application/json", token))
    }

    fun deleteAccount(token: String) {
        require(token.isNotBlank()) { "Please sign in to delete your DocNexa account." }
        val payload = JSONObject().put("confirm", true).toString().toByteArray(Charsets.UTF_8)
        val response = request(
            "POST",
            "/functions/v1/docnexa-delete-account",
            payload,
            "application/json",
            token
        )
        val json = JSONObject(response)
        if (!json.optBoolean("success", false)) {
            throw IllegalStateException(
                json.optString("error").ifBlank { "Account deletion could not be completed." }
            )
        }
    }

    fun resendConfirmation(email: String) {
        val body = JSONObject().put("type", "signup").put("email", email).toString().toByteArray()
        request("POST", "/auth/v1/resend", body, "application/json")
    }

    fun sendPasswordReset(email: String) {
        val body = JSONObject().put("email", email).toString().toByteArray()
        request("POST", "/auth/v1/recover", body, "application/json")
    }

    fun backupName(name:String):String=safeName(name)
    private fun storageConnection(method:String,path:String,token:String):HttpURLConnection=
        (URL(BASE_URL+path).openConnection() as HttpURLConnection).apply{
            requestMethod=method;connectTimeout=20000;readTimeout=60000;instanceFollowRedirects=false
            setRequestProperty("apikey",API_KEY);setRequestProperty("Authorization","Bearer "+token)
        }
    fun uploadFile(token:String,userId:String,name:String,file:java.io.File,allowed:()->Boolean){
        require(file.isFile&&file.length() in 1..BackupIntegrity.MAX_BYTES&&allowed())
        val c=storageConnection("POST","/storage/v1/object/$BUCKET/"+encodePath("$userId/"+safeName(name)),token)
        try{
            c.doOutput=true;c.setRequestProperty("Content-Type","application/pdf");c.setRequestProperty("x-upsert","false")
            c.setFixedLengthStreamingMode(file.length())
            c.outputStream.use{out->file.inputStream().use{BackupIntegrity.copy(it,out,file.length(),true,allowed)}}
            require(c.responseCode in 200..299){"Cloud upload failed (HTTP "+c.responseCode+")"}
        }finally{c.disconnect()}
    }
    fun list(token:String,userId:String):List<String>{
        val names=mutableListOf<String>()
        for(page in 0 until 20){
            val body=JSONObject().put("prefix","$userId/").put("limit",100).put("offset",page*100)
                .put("sortBy",JSONObject().put("column","name").put("order","asc")).toString().toByteArray()
            val rows=JSONArray(request("POST","/storage/v1/object/list/$BUCKET",body,"application/json",token))
            for(i in 0 until rows.length()){
                val name=rows.getJSONObject(i).optString("name")
                if(name.isNotBlank()&&!name.contains("/")&&name==safeName(name))names.add(name)
            }
            if(rows.length()<100)return names
        }
        error("Cloud inventory exceeds 2,000 files; no files overwritten")
    }
    fun downloadPdf(token:String,userId:String,name:String,out:java.io.OutputStream?,allowed:()->Boolean):String{
        require(name==safeName(name)&&allowed())
        val c=storageConnection("GET","/storage/v1/object/authenticated/$BUCKET/"+encodePath("$userId/$name"),token)
        try{
            require(c.responseCode in 200..299){"Cloud restore failed (HTTP "+c.responseCode+")"}
            return c.inputStream.use{BackupIntegrity.copy(it,out,c.contentLengthLong,true,allowed)}
        }finally{c.disconnect()}
    }
    fun pdfHash(token:String,userId:String,name:String,allowed:()->Boolean)=downloadPdf(token,userId,name,null,allowed)
    private fun safeName(name:String):String=
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"),"_").take(100).ifBlank{"Document.pdf"}

    private fun encodePath(path: String): String = path.split("/").joinToString("/") {
        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }

    private fun request(method: String, path: String, body: ByteArray?, contentType: String,
                        token: String? = null, extraHeaders: Map<String, String> = emptyMap()): String {
        return String(requestBytes(method, path, body, contentType, token, extraHeaders), Charsets.UTF_8)
    }

    private fun requestBytes(method: String, path: String, body: ByteArray?, contentType: String?,
                             token: String? = null, extraHeaders: Map<String, String> = emptyMap()): ByteArray {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("apikey", API_KEY)
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toByteArray()
            } ?: ByteArray(0)
            if (code !in 200..299) {
                val raw = String(bytes, Charsets.UTF_8)
                val message = try {
                    val json = JSONObject(raw)
                    json.optString("message")
                        .ifBlank { json.optString("msg") }
                        .ifBlank { json.optString("error_description") }
                        .ifBlank { json.optString("error") }
                } catch (_: Exception) { raw }
                if (code == 429) {
                    val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
                    val waitText = if (retryAfter != null && retryAfter > 0) {
                        " Try again in about ${(retryAfter + 59) / 60} minute(s)."
                    } else {
                        " Please wait before requesting another email, then try again."
                    }
                    throw IllegalStateException("Too many password reset or email requests.$waitText")
                }
                throw IllegalStateException("Supabase error $code: ${message.ifBlank { "Request failed" }}")
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }
}
