package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Session 2 slice 1 (red): compose + confirm gate.
// Fake Gmail server — no sign-in, never touches a real mailbox.
class EmailTest {

    @Test
    fun composeBuildsDraftWithToSubjectBody() {
        val draft = composeEmail(to = "priya@x.com", subject = "Late", body = "Running 10 min late")
        assertEquals(listOf("priya@x.com"), draft.to)
        assertEquals("Late", draft.subject)
        assertEquals("Running 10 min late", draft.body)
    }

    @Test
    fun rawMessageIsBase64UrlMime() {
        val draft = composeEmail(to = "priya@x.com", subject = "Late", body = "Running 10 min late")
        val raw = buildRawMessage(draft)
        assertFalse(raw.contains("+"))
        assertFalse(raw.contains("/"))
        assertFalse(raw.contains("="))
        val decoded = String(java.util.Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        assertTrue(decoded.contains("priya@x.com"))
        assertTrue(decoded.contains("Subject: Late"))
        assertTrue(decoded.contains("Running 10 min late"))
    }

    @Test
    fun sendBlockedUntilConfirmed() {
        val fake = FakeGmailApi()
        val draft = composeEmail(to = "priya@x.com", subject = "Late", body = "Running 10 min late")
        assertFalse(sendWithConfirm(fake, buildRawMessage(draft), confirmed = false))
        assertEquals(0, fake.sent.size)
        assertTrue(sendWithConfirm(fake, buildRawMessage(draft), confirmed = true))
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun importantMailRanksFirst() {
        val fake =
            FakeGmailApi(
                inbox =
                    listOf(
                        EmailSummary("2", "spam@x.com", "Win a prize", "click here", important = false),
                        EmailSummary("1", "boss@x.com", "Urgent: review", "please check", important = true),
                    ),
            )
        val found = readImportant(fake, max = 5)
        assertEquals("1", found[0].id)
        assertEquals("2", found[1].id)
    }

    @Test
    fun readRespectsMax() {
        val fake =
            FakeGmailApi(
                inbox =
                    listOf(
                        EmailSummary("1", "a@x.com", "One", "s1", important = true),
                        EmailSummary("2", "b@x.com", "Two", "s2", important = true),
                    ),
            )
        assertEquals(1, readImportant(fake, max = 1).size)
    }

    @Test
    fun revokeClearsTokens() {
        val tokens = TokenStore()
        tokens.gmailRefreshToken = "secret-refresh-token"
        assertTrue(tokens.isConnected())
        tokens.revoke()
        assertFalse(tokens.isConnected())
    }

    @Test
    fun verifierIsLongUrlSafeRandom() {
        val v1 = newCodeVerifier()
        val v2 = newCodeVerifier()
        assertTrue(v1.length in 43..128)
        assertTrue(v1.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(v1 != v2)
    }

    @Test
    fun challengeMatchesRfc7636Vector() {
        // Independent truth: RFC 7636 Appendix B example.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun authUrlCarriesClientChallengeScopes() {
        val url =
            buildAuthUrl(
                clientId = "abc.apps.googleusercontent.com",
                redirectUri = "com.aurviz.agent:/oauth2redirect",
                challenge = "CHALL",
                scopes = listOf("https://www.googleapis.com/auth/gmail.send"),
            )
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("client_id=abc.apps.googleusercontent.com"))
        assertTrue(url.contains("code_challenge=CHALL"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("gmail.send"))
    }

    @Test
    fun parseMessageIdsFromListJson() {
        val json = """{"messages":[{"id":"m1"},{"id":"m2"}],"resultSizeEstimate":2}"""
        assertEquals(listOf("m1", "m2"), parseMessageIds(json))
    }

    @Test
    fun parseMessageMetaMarksImportant() {
        val json =
            """
            {"id":"m1","snippet":"please check","labelIds":["INBOX","IMPORTANT"],
             "payload":{"headers":[{"name":"From","value":"boss@x.com"},{"name":"Subject","value":"Urgent"}]}}
            """.trimIndent()
        val summary = parseMessageMeta(json)
        assertEquals("m1", summary.id)
        assertEquals("boss@x.com", summary.from)
        assertEquals("Urgent", summary.subject)
        assertTrue(summary.important)
    }

    @Test
    fun exchangeFormCarriesSecretAndVerifier() {
        val form =
            buildTokenExchangeForm(
                code = "CODE",
                clientId = "ID",
                clientSecret = "SECRET",
                verifier = "VER",
                redirectUri = "http://127.0.0.1:1/callback",
            )
        assertEquals("CODE", form["code"])
        assertEquals("SECRET", form["client_secret"])
        assertEquals("VER", form["code_verifier"])
        assertEquals("authorization_code", form["grant_type"])
    }

    @Test
    fun refreshFormCarriesSecret() {
        val form = buildTokenRefreshForm(refresh = "R", clientId = "ID", clientSecret = "SECRET")
        assertEquals("R", form["refresh_token"])
        assertEquals("SECRET", form["client_secret"])
        assertEquals("refresh_token", form["grant_type"])
    }
}

class FakeGmailApi(private val inbox: List<EmailSummary> = emptyList()) : GmailApi {
    val sent = mutableListOf<String>()
    override fun send(rawBase64Url: String) {
        sent += rawBase64Url
    }
    override fun listInbox(): List<EmailSummary> = inbox.toList()
    override fun searchInbox(query: String, max: Int): List<EmailSummary> =
        inbox.filter {
            it.from.contains(query, ignoreCase = true) ||
                it.subject.contains(query, ignoreCase = true) ||
                it.snippet.contains(query, ignoreCase = true)
        }.take(max.coerceIn(1, 25))
    override fun readMessage(id: String): EmailDetail {
        val row = inbox.firstOrNull { it.id == id }
            ?: throw NoSuchElementException("unknown message: $id")
        return EmailDetail(row.id, row.from, row.subject, row.snippet, "", emailSource(row.id))
    }
}
