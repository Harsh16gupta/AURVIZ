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

// The plug shape. Real Gmail implements this with users.messages.list
// (search), users.messages.get (read), and users.messages.send.
interface GmailApi {
    fun send(rawBase64Url: String)

    fun listInbox(): List<EmailSummary>

    fun searchInbox(query: String, max: Int): List<EmailSummary>

    fun readMessage(id: String): EmailDetail
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
// Single inbox scan: one partition pass, both groups stable.
fun readImportant(api: GmailApi, max: Int): List<EmailSummary> {
    val (important, rest) = api.listInbox().partition { it.important }
    return (important + rest).take(max)
}

// Ticket 03/24: search + read for the agent loop. Read-only (ADR-0003:
// no Confirmation gate on reads); every result carries its mailbox
// source tag so the injection defences (ticket 16) can treat mail
// content as data, never instructions.

// One fully-read message. source names the mailbox origin of every
// row the Tools return, so downstream policy can tell tool data
// apart from model instructions.
data class EmailDetail(
    val id: String,
    val from: String,
    val subject: String,
    val snippet: String,
    val body: String,
    val source: String,
)

/** Mailbox origin tag for one message, carried in every Tool result. */
fun emailSource(id: String): String = "gmail:$id"

// Search the mailbox with Gmail query syntax ("from:boss subject:plan"),
// at most max rows (clamped to 1..25, the Gmail page-size window).
fun searchEmails(api: GmailApi, query: String, max: Int): List<EmailSummary> =
    api.searchInbox(query, max.coerceIn(1, 25))

// Read one message with its body. Throws when the id is unknown; the
// ToolRegistry turns that into a structured error, never a crash.
fun readEmail(api: GmailApi, id: String): EmailDetail = api.readMessage(id)

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

// Gmail bodies are base64url without padding. Restore the padding the
// transport stripped before decoding.
fun decodeGmailBody(data: String): String {
    var text = data.trim()
    val remainder = text.length % 4
    if (remainder > 0) text += "=".repeat(4 - remainder)
    return String(java.util.Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
}

// Plain-text body out of a full-format payload: recurse into multipart
// parts preferring text/plain, otherwise take the first decodable leaf
// (e.g. a singlepart text/html message).
fun extractPlainText(payload: org.json.JSONObject): String {
    val parts = payload.optJSONArray("parts")
    if (parts != null && parts.length() > 0) {
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optString("mimeType").startsWith("text/plain")) {
                val text = extractPlainText(part)
                if (text.isNotBlank()) return text
            }
        }
        for (i in 0 until parts.length()) {
            val text = extractPlainText(parts.getJSONObject(i))
            if (text.isNotBlank()) return text
        }
        return ""
    }
    val data = payload.optJSONObject("body")?.optString("data").orEmpty()
    if (data.isBlank()) return ""
    return decodeGmailBody(data)
}

// Full-format users.messages.get response: headers + snippet + decoded
// body, tagged with the mailbox source for the injection defences.
fun parseMessageFull(getJson: String): EmailDetail {
    val obj = org.json.JSONObject(getJson)
    val id = obj.getString("id")
    var from = ""
    var subject = ""
    val payload = obj.optJSONObject("payload")
    val headers = payload?.optJSONArray("headers")
    if (headers != null) {
        for (i in 0 until headers.length()) {
            val header = headers.getJSONObject(i)
            when (header.optString("name").lowercase()) {
                "from" -> from = header.optString("value")
                "subject" -> subject = header.optString("value")
            }
        }
    }
    return EmailDetail(
        id = id,
        from = from,
        subject = subject,
        snippet = obj.optString("snippet"),
        body = payload?.let(::extractPlainText).orEmpty(),
        source = emailSource(id),
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

// --- Ticket 04/24: gated send executor + deterministic policy seam. ---
// ADR-0003: send_email is HIGH risk; the Confirmation gate lives in the
// ToolRegistry, so the model has no path around it. The Confirmation decision travels
// out-of-band (the `approved` flag comes from the gate, never from LLM
// args, or the model could forge it). Plain registry execute() always
// passes approved=false and refuses without touching the transport.
//
// The SendPolicy seam runs after the gate, before any transport touch:
// DefaultSendPolicy holds malformed, bulk, and exfil-shaped sends
// (ticket 12/25).
sealed interface SendPolicyVerdict {
    data object Allow : SendPolicyVerdict

    data class Deny(val reason: String) : SendPolicyVerdict
}

fun interface SendPolicy {
    fun check(draft: EmailDraft): SendPolicyVerdict
}

object AllowAllSendPolicy : SendPolicy {
    override fun check(draft: EmailDraft): SendPolicyVerdict = SendPolicyVerdict.Allow
}

// Ticket 12/25: the deterministic pre-send check (ADR-0003, gaps §3E).
// Runs after the Confirmation gate, before any transport touch, no
// matter what the model said: even a gate Approved cannot send past a
// Deny. Three narrow holds, each JVM-tested at the Confirmation card level (ApprovalCardTest):
// malformed recipients, bulk-recipient sends, and bulk forwards of
// mailbox content (the evil-forward exfil shape: pasted "From:" lines
// and "gmail:" source tags from tool results). Ordinary sends pass.
class DefaultSendPolicy(
    private val maxRecipients: Int = 5,
    private val maxEmbeddedMails: Int = 3,
) : SendPolicy {
    override fun check(draft: EmailDraft): SendPolicyVerdict {
        val to = draft.to.map { it.trim() }.filter { it.isNotEmpty() }
        val malformed = to.firstOrNull { !isPlausibleEmail(it) }
        if (malformed != null) {
            return SendPolicyVerdict.Deny("recipient '$malformed' is not a plausible email address")
        }
        if (to.size > maxRecipients) {
            return SendPolicyVerdict.Deny("bulk send to ${to.size} recipients (max $maxRecipients)")
        }
        if (embeddedMailCount(draft.body) >= maxEmbeddedMails) {
            return SendPolicyVerdict.Deny("bulk forward of mailbox content")
        }
        return SendPolicyVerdict.Allow
    }
}

/** One @ with non-blank local and domain parts, no spaces. A shape
 *  hold, not deliverability proof. */
fun isPlausibleEmail(address: String): Boolean {
    if (" " in address) return false
    val parts = address.split("@")
    return parts.size == 2 && parts[0].isNotBlank() && "." in parts[1] && parts[1].trim('.').isNotBlank()
}

/** How many pasted mailbox messages a body carries: "From:" header
 *  lines plus "gmail:" source tags from tool results. A single quoted
 *  reply stays under the hold; forwarding the last 10 mails trips it. */
fun embeddedMailCount(body: String): Int {
    val fromLines = body.lines().count { it.trimStart().startsWith("From:", ignoreCase = true) }
    val sourceTags = Regex("gmail:").findAll(body).count()
    return fromLines + sourceTags
}

/** Header values must not carry CR/LF: a smuggled newline in To or
 *  Subject would split the MIME into attacker-chosen headers on the wire.
 *  The body rides after the blank line, so its newlines are content. */
fun headerValueHasNewline(value: String): Boolean = value.any { it == '\r' || it == '\n' }

// Gated send: validates the draft, rejects header newlines, demands gate
// Confirmation, runs the deterministic policy check, then sends exactly one
// MIME message built from the approved To/Subject/Body. Never throws:
// every failure comes back as a structured Failure the model can react
// to, and the transport is untouched unless every check passes.
fun executeSendEmail(
    api: GmailApi?,
    draft: EmailDraft,
    approved: Boolean,
    policy: SendPolicy = AllowAllSendPolicy,
): ToolResult {
    if (api == null) {
        return ToolResult.Failure("gmail is not connected", retryable = false)
    }
    val to = draft.to.map { it.trim() }.filter { it.isNotEmpty() }
    if (to.isEmpty()) {
        return ToolResult.Failure(
            "missing required argument 'to' for tool 'send_email'",
            retryable = true,
        )
    }
    if (draft.subject.isBlank()) {
        return ToolResult.Failure(
            "missing required argument 'subject' for tool 'send_email'",
            retryable = true,
        )
    }
    if (draft.body.isBlank()) {
        return ToolResult.Failure(
            "missing required argument 'body' for tool 'send_email'",
            retryable = true,
        )
    }
    if (to.any(::headerValueHasNewline) || headerValueHasNewline(draft.subject)) {
        return ToolResult.Failure(
            "header value must not contain CR/LF; nothing was sent",
            retryable = true,
        )
    }
    if (!approved) {
        return ToolResult.Failure(
            "tool 'send_email' requires confirmation and was not confirmed; nothing was sent",
            retryable = false,
        )
    }
    when (val verdict = policy.check(draft)) {
        is SendPolicyVerdict.Deny ->
            return ToolResult.Failure(
                "tool 'send_email' blocked by policy: ${verdict.reason}",
                retryable = false,
            )
        SendPolicyVerdict.Allow -> Unit
    }
    return try {
        api.send(buildRawMessage(draft.copy(to = to)))
        ToolResult.Success(mapOf("sent" to true, "to" to to, "subject" to draft.subject))
    } catch (e: Exception) {
        ToolResult.Failure("tool 'send_email' failed: ${e.message}", retryable = false)
    }
}
