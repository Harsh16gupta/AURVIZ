package com.aurviz.agent

// Session 2: email = compose + send (gated) + read-important.
// Pure decision logic, JVM-testable. Real Gmail HTTP plugs in
// through GmailApi; the unit tests use FakeGmailApi instead.

// What the user approved in the preview. Nothing sends before Confirm.
data class EmailDraft(
    val to: List<String>,
    val subject: String,
    val body: String,
)

// The plug shape. Real Gmail implements this with users.messages.send.
interface GmailApi {
    fun send(rawBase64Url: String)

    fun listInbox(): List<EmailSummary>
}

// One row from the mailbox. important = unread + starred/boss-like,
// decided by the real implementation; ranking below only sorts by it.
data class EmailSummary(
    val id: String,
    val from: String,
    val subject: String,
    val snippet: String,
    val important: Boolean,
)

// Read the mailbox: important first, then the rest, at most max rows.
fun readImportant(api: GmailApi, max: Int): List<EmailSummary> =
    (api.listInbox().filter { it.important } + api.listInbox().filter { !it.important }).take(max)

// Where the Gmail sign-in tokens live. revoke() = disconnect:
// tokens gone, nothing can send until the user connects again.
// (#8 moves these into encrypted storage; memory-only for now.)
class TokenStore {
    var gmailAccessToken: String? = null
    var gmailRefreshToken: String? = null
    var gmailAccount: String? = null

    fun isConnected(): Boolean = !gmailRefreshToken.isNullOrBlank()

    fun revoke() {
        gmailAccessToken = null
        gmailRefreshToken = null
        gmailAccount = null
    }
}

// --- OAuth login (PKCE): proves the sign-in request really came from us. ---
// verifier = one-time secret; challenge = its fingerprint (RFC 7636).

fun newCodeVerifier(): String {
    val random = java.security.SecureRandom()
    val bytes = ByteArray(48)
    random.nextBytes(bytes)
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun codeChallenge(verifier: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
}

fun buildAuthUrl(clientId: String, redirectUri: String, challenge: String, scopes: List<String>): String {
    fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    return "https://accounts.google.com/o/oauth2/v2/auth?" +
        "client_id=${enc(clientId)}" +
        "&redirect_uri=${enc(redirectUri)}" +
        "&response_type=code" +
        "&scope=${enc(scopes.joinToString(" "))}" +
        "&code_challenge=${enc(challenge)}" +
        "&code_challenge_method=S256" +
        "&access_type=offline" +
        "&prompt=consent"
}

// --- Token-swap forms (pure, so a missing field fails a test, not a sign-in).
// Desktop IDs must send client_secret + the PKCE verifier together.

fun buildTokenExchangeForm(
    code: String,
    clientId: String,
    clientSecret: String,
    verifier: String,
    redirectUri: String,
): Map<String, String> =
    mapOf(
        "code" to code,
        "client_id" to clientId,
        "client_secret" to clientSecret,
        "code_verifier" to verifier,
        "redirect_uri" to redirectUri,
        "grant_type" to "authorization_code",
    )

fun buildTokenRefreshForm(refresh: String, clientId: String, clientSecret: String): Map<String, String> =
    mapOf(
        "refresh_token" to refresh,
        "client_id" to clientId,
        "client_secret" to clientSecret,
        "grant_type" to "refresh_token",
    )

// --- Gmail response parsing (pure; the HTTP stays in GmailHttpApi). ---

fun parseMessageIds(listJson: String): List<String> {
    val obj = org.json.JSONObject(listJson)
    val arr = obj.optJSONArray("messages") ?: return emptyList()
    return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
}

fun parseMessageMeta(getJson: String): EmailSummary {
    val obj = org.json.JSONObject(getJson)
    val labels = mutableListOf<String>()
    val rawLabels = obj.optJSONArray("labelIds")
    if (rawLabels != null) {
        for (i in 0 until rawLabels.length()) labels += rawLabels.getString(i)
    }
    var from = ""
    var subject = ""
    val headers = obj.optJSONObject("payload")?.optJSONArray("headers")
    if (headers != null) {
        for (i in 0 until headers.length()) {
            val header = headers.getJSONObject(i)
            when (header.optString("name").lowercase()) {
                "from" -> from = header.optString("value")
                "subject" -> subject = header.optString("value")
            }
        }
    }
    return EmailSummary(
        id = obj.getString("id"),
        from = from,
        subject = subject,
        snippet = obj.optString("snippet"),
        important = "IMPORTANT" in labels,
    )
}

// Turn "email Priya ..." into a draft. No network, no sending.
fun composeEmail(to: String, subject: String, body: String): EmailDraft =
    EmailDraft(to = listOf(to), subject = subject, body = body)

// Build the exact Gmail format: MIME text, base64URL-encoded, no padding.
// ("URL-safe" = no + / or = characters, so it travels in JSON safely.)
fun buildRawMessage(draft: EmailDraft): String {
    val mime =
        "To: ${draft.to.joinToString(", ")}\r\n" +
            "Subject: ${draft.subject}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "\r\n" +
            draft.body
    return java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(mime.toByteArray(Charsets.UTF_8))
}

// The confirm gate: preview first, send only when confirmed == true.
// Returns true only if the message actually went out.
fun sendWithConfirm(api: GmailApi, rawBase64Url: String, confirmed: Boolean): Boolean {
    if (!confirmed) return false
    api.send(rawBase64Url)
    return true
}
