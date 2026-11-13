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

    data class AuthResult(val accessToken: String, val userId: String, val message: String)

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
        return AuthResult(token, userId, "Signed in")
    }

    fun resendConfirmation(email: String) {
        val body = JSONObject().put("type", "signup").put("email", email).toString().toByteArray()
        request("POST", "/auth/v1/resend", body, "application/json")
    }

    fun sendPasswordReset(email: String) {
        val body = JSONObject().put("email", email).toString().toByteArray()
        request("POST", "/auth/v1/recover", body, "application/json")
    }

    fun upload(token: String, userId: String, fileName: String, bytes: ByteArray) {
        val path = "$userId/${safeName(fileName)}"
        request("POST", "/storage/v1/object/$BUCKET/${encodePath(path)}", bytes, "application/pdf", token,
            mapOf("x-upsert" to "true"))
    }

    fun list(token: String, userId: String): List<String> {
        val body = JSONObject().put("prefix", "$userId/")
            .put("limit", 1000).put("offset", 0).put("sortBy", JSONObject().put("column", "name").put("order", "asc"))
            .toString().toByteArray()
        val raw = request("POST", "/storage/v1/object/list/$BUCKET", body, "application/json", token)
        val array = JSONArray(raw)
        val names = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val name = array.getJSONObject(i).optString("name")
            if (name.isNotBlank() && !name.contains("/")) names.add(name)
        }
        return names
    }

    fun download(token: String, userId: String, fileName: String): ByteArray =
        requestBytes("GET", "/storage/v1/object/authenticated/$BUCKET/${encodePath("$userId/${safeName(fileName)}")}", null, null, token)

    private fun safeName(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifBlank { "Document.pdf" }

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
                val message = try {
                    val json = JSONObject(String(bytes, Charsets.UTF_8))
                    json.optString("message").ifBlank { json.optString("error_description").ifBlank { json.optString("error") } }
                } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
                throw IllegalStateException("Supabase error $code: ${message.ifBlank { "Request failed" }}")
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }
}
