package com.aurviz.agent

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// Session 2 thin wiring: real Gmail over HTTPS (OkHttp, already a dependency).
// Parsing stays in Email.kt (unit-tested); this file only moves bytes.
// Refreshes the access token once on 401, like the T4 provider fallback.

const val GMAIL_REDIRECT_URI = "com.aurviz.agent:/oauth2redirect"

val GMAIL_SCOPES =
    listOf(
        "https://www.googleapis.com/auth/gmail.send",
        "https://www.googleapis.com/auth/gmail.readonly",
    )

class GmailException(val httpCode: Int, message: String) : Exception(message)

class GmailHttpApi(
    private val tokens: TokenStore,
    private val clientId: String,
    private val clientSecret: String = "",
    var redirectUri: String = GMAIL_REDIRECT_URI,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
) : GmailApi {

    // Step 2 of sign-in: swap the browser code (+ our PKCE secret) for tokens.
    fun exchangeCode(code: String, verifier: String) {
        val form = buildTokenExchangeForm(code, clientId, clientSecret, verifier, redirectUri)
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
        client.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GmailException(response.code, "token exchange: HTTP ${response.code} ${text.take(200).replace("\n", " ")}")
            }
            val json = JSONObject(text)
            tokens.gmailAccessToken = json.getString("access_token")
            if (json.has("refresh_token")) tokens.gmailRefreshToken = json.getString("refresh_token")
        }
    }

    private fun refreshAccess() {
        val refresh = tokens.gmailRefreshToken ?: throw GmailException(401, "not connected")
        val form = buildTokenRefreshForm(refresh, clientId, clientSecret)
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
        client.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GmailException(response.code, "token refresh: HTTP ${response.code} ${text.take(200).replace("\n", " ")}")
            }
            tokens.gmailAccessToken = JSONObject(text).getString("access_token")
        }
    }

    private fun get(path: String): String = authed("GET", path, null)

    private fun post(path: String, json: String): String = authed("POST", path, json)

    // One call with the current token; on 401 refresh once and retry once.
    private fun authed(method: String, path: String, json: String?): String {
        val token = tokens.gmailAccessToken ?: throw GmailException(401, "not connected")
        try {
            return rawCall(method, path, json, token)
        } catch (e: GmailException) {
            if (e.httpCode != 401) throw e
            refreshAccess()
            val retry = tokens.gmailAccessToken ?: throw GmailException(401, "not connected")
            return rawCall(method, path, json, retry)
        }
    }

    private fun rawCall(method: String, path: String, json: String?, token: String): String {
        val builder =
            Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me$path")
                .addHeader("Authorization", "Bearer $token")
        if (method == "POST") {
            builder.post((json ?: "{}").toRequestBody("application/json".toMediaType()))
        } else {
            builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GmailException(response.code, "Gmail: HTTP ${response.code} ${text.take(200).replace("\n", " ")}")
            }
            return text
        }
    }

    override fun send(rawBase64Url: String) {
        post("/messages/send", JSONObject().put("raw", rawBase64Url).toString())
    }

    override fun listInbox(): List<EmailSummary> {
        val ids = parseMessageIds(get("/messages?maxResults=10&q=is:unread"))
        return ids.map { id ->
            parseMessageMeta(get("/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject"))
        }
    }

    // Ticket 03/24: Gmail search syntax over users.messages.list, then one
    // metadata fetch per hit for the From/Subject rows the loop reads.
    override fun searchInbox(query: String, max: Int): List<EmailSummary> {
        val limit = max.coerceIn(1, 25)
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val ids = parseMessageIds(get("/messages?q=$encoded&maxResults=$limit"))
        return ids.take(limit).map { id ->
            parseMessageMeta(get("/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject"))
        }
    }

    // Ticket 03/24: full-format users.messages.get, body decoded in Email.kt.
    override fun readMessage(id: String): EmailDetail =
        parseMessageFull(get("/messages/$id?format=full"))

    fun fetchProfileEmail(): String {
        val json = JSONObject(get("/profile"))
        return json.getString("emailAddress")
    }
}
