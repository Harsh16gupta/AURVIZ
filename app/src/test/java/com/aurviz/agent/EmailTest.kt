package com.aurviz.agent

import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun crlfInToIsRejectedWithoutTransport() {
        val fake = FakeGmailApi()
        val draft = composeEmail(to = "a@x.com\r\nBcc: evil@x.com", subject = "Hi", body = "hello")
        val result = executeSendEmail(fake, draft, approved = true, policy = AllowAllSendPolicy)
        assertTrue(result is ToolResult.Failure)
        assertEquals(0, fake.sent.size)
    }

    @Test
    fun crlfInSubjectIsRejectedWithoutTransport() {
        val fake = FakeGmailApi()
        val draft = composeEmail(to = "a@x.com", subject = "Hi\r\nX-Injected: yes", body = "hello")
        val result = executeSendEmail(fake, draft, approved = true, policy = AllowAllSendPolicy)
        assertTrue(result is ToolResult.Failure)
        assertEquals(0, fake.sent.size)
    }

    @Test
    fun multilineBodyStillSends() {
        val fake = FakeGmailApi()
        val draft = composeEmail(to = "a@x.com", subject = "Hi", body = "line one\nline two")
        val result = executeSendEmail(fake, draft, approved = true, policy = AllowAllSendPolicy)
        assertTrue(result is ToolResult.Success)
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

    // NG-7: one inbox scan per read, important-first (stable), then rest, capped.
    @Test
    fun readScansInboxOncePerRead() {
        var scans = 0
        val inner =
            FakeGmailApi(
                inbox =
                    listOf(
                        EmailSummary("1", "a@x.com", "One", "s1", important = true),
                        EmailSummary("2", "b@x.com", "Two", "s2", important = false),
                    ),
            )
        val counting =
            object : GmailApi by inner {
                override fun listInbox(): List<EmailSummary> {
                    scans++
                    return inner.listInbox()
                }
            }
        readImportant(counting, max = 5)
        assertEquals(1, scans)
    }

    @Test
    fun readKeepsStableOrderWithinEachGroup() {
        val fake =
            FakeGmailApi(
                inbox =
                    listOf(
                        EmailSummary("1", "a@x.com", "One", "s1", important = false),
                        EmailSummary("2", "b@x.com", "Two", "s2", important = true),
                        EmailSummary("3", "c@x.com", "Three", "s3", important = false),
                        EmailSummary("4", "d@x.com", "Four", "s4", important = true),
                    ),
            )
        val ids = readImportant(fake, max = 10).map { it.id }
        assertEquals(listOf("2", "4", "1", "3"), ids)
    }

    @Test
    fun readCapTruncationKeepsImportantFirst() {
        val fake =
            FakeGmailApi(
                inbox =
                    listOf(
                        EmailSummary("1", "a@x.com", "One", "s1", important = false),
                        EmailSummary("2", "b@x.com", "Two", "s2", important = true),
                        EmailSummary("3", "c@x.com", "Three", "s3", important = true),
                        EmailSummary("4", "d@x.com", "Four", "s4", important = false),
                    ),
            )
        val ids = readImportant(fake, max = 2).map { it.id }
        assertEquals(listOf("2", "3"), ids)
    }

    @Test
    fun readEmptyInboxReturnsEmpty() {
        assertEquals(0, readImportant(FakeGmailApi(), max = 5).size)
    }

    @Test
    fun readAllImportantPreservesOrder() {
        val fake =
            FakeGmailApi(
                inbox =
                    listOf(
                        EmailSummary("1", "a@x.com", "One", "s1", important = true),
                        EmailSummary("2", "b@x.com", "Two", "s2", important = true),
                    ),
            )
        val ids = readImportant(fake, max = 10).map { it.id }
        assertEquals(listOf("1", "2"), ids)
    }

    @Test
    fun readNoneImportantPreservesOrder() {
        val fake =
            FakeGmailApi(
                inbox =
                    listOf(
                        EmailSummary("1", "a@x.com", "One", "s1", important = false),
                        EmailSummary("2", "b@x.com", "Two", "s2", important = false),
                    ),
            )
        val ids = readImportant(fake, max = 10).map { it.id }
        assertEquals(listOf("1", "2"), ids)
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

    // --- Ticket #107 (T2, fix 2): refresh persistence + null-access
    // bootstrap, over an interceptor-faked OkHttpClient (no network). ---

    private data class FakeHttpReply(val code: Int, val body: String)

    private fun fakeGmailClient(handler: (String) -> FakeHttpReply): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val reply = handler(request.url.toString())
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(reply.code)
                    .message("test")
                    .body(reply.body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    private fun tokenHandler(tokenReply: FakeHttpReply, gmailReply: FakeHttpReply): (String) -> FakeHttpReply =
        { url ->
            if (url.startsWith("https://oauth2.googleapis.com/token")) tokenReply else gmailReply
        }

    @Test
    fun exchangeCodePersistsTokensViaCallback() {
        val tokens = TokenStore()
        var savedAccess: String? = null
        var savedRefresh: String? = null
        var calls = 0
        val api =
            GmailHttpApi(
                tokens,
                "ID",
                "SECRET",
                onTokensUpdated = {
                    calls++
                    savedAccess = it.gmailAccessToken
                    savedRefresh = it.gmailRefreshToken
                },
                client = fakeGmailClient(tokenHandler(
                    FakeHttpReply(200, """{"access_token":"AT","refresh_token":"RT"}"""),
                    FakeHttpReply(404, "{}"),
                )),
            )
        api.exchangeCode("CODE", "VERIFIER")
        assertEquals("AT", tokens.gmailAccessToken)
        assertEquals("RT", tokens.gmailRefreshToken)
        assertEquals(1, calls)
        assertEquals("AT", savedAccess)
        assertEquals("RT", savedRefresh)
    }

    @Test
    fun blankAccessTokenBootstrapsRefreshBeforeFirstCall() {
        // Cold start from the vault with only the refresh token persisted.
        val tokens = TokenStore()
        tokens.gmailRefreshToken = "RT"
        var calls = 0
        val api =
            GmailHttpApi(
                tokens,
                "ID",
                "SECRET",
                onTokensUpdated = { calls++ },
                client = fakeGmailClient(tokenHandler(
                    FakeHttpReply(200, """{"access_token":"FRESH"}"""),
                    FakeHttpReply(200, """{"messages":[]}"""),
                )),
            )
        assertEquals(0, api.listInbox().size)
        assertEquals("FRESH", tokens.gmailAccessToken)
        assertTrue("rotated access token must persist", calls >= 1)
    }

    @Test
    fun invalidGrantRefreshClearsStaleAccessToken() {
        val tokens = TokenStore()
        tokens.gmailAccessToken = "STALE"
        tokens.gmailRefreshToken = "RT"
        var savedAccess: String? = "sentinel"
        var calls = 0
        val api =
            GmailHttpApi(
                tokens,
                "ID",
                "SECRET",
                onTokensUpdated = {
                    calls++
                    savedAccess = it.gmailAccessToken
                },
                client = fakeGmailClient(tokenHandler(
                    FakeHttpReply(400, """{"error":"invalid_grant"}"""),
                    FakeHttpReply(401, """{"error":"invalid credentials"}"""),
                )),
            )
        try {
            api.listInbox()
            fail("invalid_grant refresh must throw")
        } catch (e: GmailException) {
            assertEquals(400, e.httpCode)
        }
        assertNull("dead grant must drop the stale access token", tokens.gmailAccessToken)
        assertTrue("vault must persist the cleared token", calls >= 1)
        assertNull(savedAccess)
    }

    @Test
    fun disconnectedApiStillFailsFastWithoutRefreshToken() {
        val tokens = TokenStore()
        val api =
            GmailHttpApi(
                tokens,
                "ID",
                "SECRET",
                client = fakeGmailClient(tokenHandler(
                    FakeHttpReply(200, """{"access_token":"AT"}"""),
                    FakeHttpReply(200, """{"messages":[]}"""),
                )),
            )
        try {
            api.listInbox()
            fail("must throw when not connected")
        } catch (e: GmailException) {
            assertEquals(401, e.httpCode)
        }
    }

    // --- Ticket #107 (T2, fix 3): LoopbackReceiver socket lifecycle. ---
    // Real loopback sockets (host JVM) — the sign-in socket must close on
    // paste/dismiss/re-entry/clear instead of leaking, and a blocked
    // waitForCode must wake promptly when closed. Debug logs stay gated.

    @Test
    fun loopbackCloseIsIdempotent() {
        val receiver = LoopbackReceiver()
        receiver.close()
        receiver.close()
    }

    @Test
    fun loopbackWaitAbortsWhenClosedWhileWaiting() {
        // waitForCode itself needs a real phone (android.util.Log is a
        // unit-test stub), so this pins the abort path in source: a
        // SocketException from accept() — what close() triggers in a
        // blocked waiter — returns null instead of crashing the coroutine.
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) break
            dir = dir.parentFile ?: throw AssertionError("Could not locate app module dir from user.dir")
        }
        val auth = File(dir, "src/main/java/com/aurviz/agent/LoopbackAuth.kt").readText()
        assertTrue(auth.contains("catch (e: java.net.SocketException)"))
        assertTrue(auth.contains("return null"))
    }

    @Test
    fun loopbackCloseWiredOnPasteDismissClearAndLogsGated() {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) break
            dir = dir.parentFile ?: throw AssertionError("Could not locate app module dir from user.dir")
        }
        val auth = File(dir, "src/main/java/com/aurviz/agent/LoopbackAuth.kt").readText()
        assertTrue(auth.contains("fun close()"))
        // Exactly one raw Log.d — inside the BuildConfig.DEBUG-gated helper.
        assertEquals(1, "android.util.Log.d".toRegex().findAll(auth).count())
        assertTrue(auth.contains("BuildConfig.DEBUG"))
        val vm = File(dir, "src/main/java/com/aurviz/agent/ui/AgentViewModel.kt").readText()
        assertTrue(vm.contains("private var activeLoopback: LoopbackReceiver? = null"))
        assertTrue(vm.contains("activeLoopback = receiver"))
        assertTrue(vm.contains("activeLoopback = null"))
        assertTrue(vm.contains("activeLoopback?.close()"))
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
