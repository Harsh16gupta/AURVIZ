package com.aurviz.agent

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #67 T2: bounded creation-time consent + fire-time exact-match judge.
// Granting path saves exact recipient set + subject/body template + owning
// Schedule id; fire-time runs only the exact match (plus SendPolicy), every
// drift defers to PendingApproval. Fake transports only.
class ScheduledConsentTest {

    private class FakeGmail(var expired: Boolean = false) : GmailApi {
        val sentRaws = mutableListOf<String>()
        private val rows = listOf(EmailSummary("m1", "boss@work.com", "Q3 plan", "draft", true))
        private fun auth() {
            if (expired) throw GmailException(401, "gmail is not connected: token expired")
        }
        override fun send(rawBase64Url: String) {
            sentRaws += rawBase64Url
        }
        override fun listInbox(): List<EmailSummary> {
            auth()
            return rows
        }
        override fun searchInbox(query: String, max: Int): List<EmailSummary> {
            auth()
            return rows.take(max.coerceIn(1, 25))
        }
        override fun readMessage(id: String): EmailDetail {
            auth()
            val row = rows.first { it.id == id }
            return EmailDetail(row.id, row.from, row.subject, row.snippet, "body text", emailSource(row.id))
        }
    }

    private fun fullRegistry(gmail: FakeGmail, schedules: InMemoryScheduleStore = InMemoryScheduleStore(), memories: InMemoryStore = InMemoryStore()): ToolRegistry =
        defaultToolRegistry(gmailApi = gmail, scheduleStore = schedules, memoryStore = memories)

    private fun answer(text: String) = LlmResult(toolCalls = emptyList(), content = text)

    private fun sendCall(to: String, subject: String = "Hi", body: String = "hello") =
        ToolCall("c1", "send_email", mapOf("to" to to, "subject" to subject, "body" to body))

    // --- Read allowlist runs unattended even with a consent store present. ---

    @Test
    fun readsRunUnattendedWithConsentStorePresent() {
        val gmail = FakeGmail()
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(listOf(ToolCall("c1", "search_emails", mapOf("query" to "boss"))), null),
                    answer("1 important email"),
                ),
            )
        val runs = InMemoryScheduleRunStore()
        val approvals = InMemoryPendingApprovalStore()
        val outcome =
            runScheduledRoutine(
                Schedule("sched_1", "check emails", 9, 0),
                1_000L,
                llm,
                fullRegistry(gmail),
                runs,
                approvals,
                consents = InMemoryScheduledConsentStore(),
                nowMs = { 1_050L },
            )
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.SUCCESS, outcome.run.status)
        assertTrue(approvals.list().isEmpty())
        assertTrue(gmail.sentRaws.isEmpty())
    }

    // --- Writes become held when no consent exists. ---

    @Test
    fun sendAndScheduleWritesHeldWithoutConsent() {
        val gmail = FakeGmail()
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(
                        listOf(
                            ToolCall("c1", "send_email", mapOf("to" to "x@y.z", "subject" to "Hi", "body" to "b")),
                            ToolCall("c2", "create_schedule", mapOf("task" to "spam", "when" to "at 9am")),
                        ),
                        null,
                    ),
                    answer("held"),
                ),
            )
        val approvals = InMemoryPendingApprovalStore()
        val outcome =
            runScheduledRoutine(
                Schedule("sched_1", "check emails", 9, 0),
                1_000L,
                llm,
                fullRegistry(gmail),
                InMemoryScheduleRunStore(),
                approvals,
                nowMs = { 1_050L },
            )
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.BLOCKED_WRITE, outcome.run.status)
        assertTrue(gmail.sentRaws.isEmpty())
        assertEquals(2, approvals.list().size)
    }

    // --- Exact match runs, no approval row. ---

    @Test
    fun exactMatchConsentRunsOnceWithNoApprovalRow() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(sendCall("friend@work.com")), null), answer("sent")))
        val approvals = InMemoryPendingApprovalStore()
        val outcome =
            runScheduledRoutine(
                Schedule("sched_1", "send hi", 9, 0),
                1_000L,
                llm,
                fullRegistry(gmail),
                InMemoryScheduleRunStore(),
                approvals,
                consents = consents,
                nowMs = { 1_050L },
            )
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.SUCCESS, outcome.run.status)
        assertEquals(1, gmail.sentRaws.size)
        assertTrue(approvals.list().isEmpty())
        assertTrue(outcome.blocked.isEmpty())
    }

    // --- Drift defers: recipient, bulk expansion, subject, body. ---

    @Test
    fun recipientDriftDefersToPendingApproval() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(sendCall("stranger@evil.com")), null), answer("held")))
        val approvals = InMemoryPendingApprovalStore()
        val outcome =
            runScheduledRoutine(
                Schedule("sched_1", "send hi", 9, 0),
                1_000L,
                llm,
                fullRegistry(gmail),
                InMemoryScheduleRunStore(),
                approvals,
                consents = consents,
                nowMs = { 1_050L },
            )
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        assertEquals(ScheduleRunStatus.BLOCKED_WRITE, (outcome as ScheduledRunOutcome.Ran).run.status)
        assertTrue(gmail.sentRaws.isEmpty())
        assertEquals(1, approvals.list().size)
        assertEquals("stranger@evil.com", approvals.list().single().arguments["to"])
    }

    @Test
    fun bulkExpansionDefersToPendingApproval() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        val llm =
            FakeLlmProvider(
                listOf(LlmResult(listOf(sendCall("friend@work.com, extra@work.com")), null), answer("held")),
            )
        val approvals = InMemoryPendingApprovalStore()
        val outcome =
            runScheduledRoutine(
                Schedule("sched_1", "send hi", 9, 0),
                1_000L,
                llm,
                fullRegistry(gmail),
                InMemoryScheduleRunStore(),
                approvals,
                consents = consents,
                nowMs = { 1_050L },
            )
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        assertEquals(ScheduleRunStatus.BLOCKED_WRITE, (outcome as ScheduledRunOutcome.Ran).run.status)
        assertTrue(gmail.sentRaws.isEmpty())
        assertEquals(1, approvals.list().size)
    }

    @Test
    fun subjectDriftDefers() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(sendCall("friend@work.com", subject = "Changed")), null), answer("held")))
        val approvals = InMemoryPendingApprovalStore()
        runScheduledRoutine(
            Schedule("sched_1", "send hi", 9, 0),
            1_000L,
            llm,
            fullRegistry(gmail),
            InMemoryScheduleRunStore(),
            approvals,
            consents = consents,
            nowMs = { 1_050L },
        )
        assertTrue(gmail.sentRaws.isEmpty())
        assertEquals(1, approvals.list().size)
    }

    @Test
    fun bodyDriftDefers() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(sendCall("friend@work.com", body = "tampered")), null), answer("held")))
        val approvals = InMemoryPendingApprovalStore()
        runScheduledRoutine(
            Schedule("sched_1", "send hi", 9, 0),
            1_000L,
            llm,
            fullRegistry(gmail),
            InMemoryScheduleRunStore(),
            approvals,
            consents = consents,
            nowMs = { 1_050L },
        )
        assertTrue(gmail.sentRaws.isEmpty())
        assertEquals(1, approvals.list().size)
    }

    @Test
    fun policyFailureDefersEvenOnExactMatch() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        // Exfil-shaped template: exact match, but DefaultSendPolicy denies bulk forward.
        val exfilBody = "From: a@b.c\nFrom: d@e.f\nFrom: g@h.i\ngmail:m1 gmail:m2 gmail:m3"
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", exfilBody)
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(sendCall("friend@work.com", body = exfilBody)), null), answer("held")))
        val approvals = InMemoryPendingApprovalStore()
        val outcome =
            runScheduledRoutine(
                Schedule("sched_1", "send hi", 9, 0),
                1_000L,
                llm,
                fullRegistry(gmail),
                InMemoryScheduleRunStore(),
                approvals,
                consents = consents,
                nowMs = { 1_050L },
            )
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        assertTrue(gmail.sentRaws.isEmpty())
        assertEquals(1, approvals.list().size)
    }

    // --- Schedule writes are never covered by send consent. ---

    @Test
    fun scheduleWritesNeverCoveredBySendConsent() {
        val gmail = FakeGmail()
        val schedules = InMemoryScheduleStore()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(listOf(ToolCall("c1", "create_schedule", mapOf("task" to "spam", "when" to "at 9am"))), null),
                    answer("held"),
                ),
            )
        val approvals = InMemoryPendingApprovalStore()
        runScheduledRoutine(
            Schedule("sched_1", "manage", 9, 0),
            1_000L,
            llm,
            fullRegistry(gmail, schedules),
            InMemoryScheduleRunStore(),
            approvals,
            consents = consents,
            nowMs = { 1_050L },
        )
        assertTrue(schedules.list().isEmpty())
        assertEquals(1, approvals.list().size)
        assertEquals("create_schedule", approvals.list().single().toolName)
    }

    // --- Consent is scoped to the owning schedule. ---

    @Test
    fun consentDoesNotLeakAcrossSchedules() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_A", setOf("friend@work.com"), "Hi", "hello")
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(sendCall("friend@work.com")), null), answer("held")))
        val approvals = InMemoryPendingApprovalStore()
        runScheduledRoutine(
            Schedule("sched_B", "send hi", 9, 0),
            1_000L,
            llm,
            fullRegistry(gmail),
            InMemoryScheduleRunStore(),
            approvals,
            consents = consents,
            nowMs = { 1_050L },
        )
        assertTrue(gmail.sentRaws.isEmpty())
        assertEquals(1, approvals.list().size)
    }

    // --- Double-fire never double-sends a consented run. ---

    @Test
    fun consentedDoubleFireRunsLoopOnce() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(sendCall("friend@work.com")), null), answer("sent")))
        val runs = InMemoryScheduleRunStore()
        val approvals = InMemoryPendingApprovalStore()
        val registry = fullRegistry(gmail)
        val schedule = Schedule("sched_1", "send hi", 9, 0)
        val first = runScheduledRoutine(schedule, 1_000L, llm, registry, runs, approvals, consents = consents, nowMs = { 1_050L })
        assertTrue(first is ScheduledRunOutcome.Ran)
        val seenAfterFirst = llm.seenMessages.size
        val second = runScheduledRoutine(schedule, 1_000L, llm, registry, runs, approvals, consents = consents, nowMs = { 1_050L })
        assertTrue(second is ScheduledRunOutcome.DuplicateSkipped)
        assertEquals(1, gmail.sentRaws.size)
        assertEquals(seenAfterFirst, llm.seenMessages.size)
    }

    // --- Double-approve on a held drift sends exactly once. ---

    @Test
    fun driftHeldApproveIsExactlyOnce() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(sendCall("stranger@evil.com")), null), answer("held")))
        val approvals = InMemoryPendingApprovalStore()
        val registry = fullRegistry(gmail)
        runScheduledRoutine(
            Schedule("sched_1", "send hi", 9, 0),
            1_000L,
            llm,
            registry,
            InMemoryScheduleRunStore(),
            approvals,
            consents = consents,
            nowMs = { 1_050L },
        )
        val held = approvals.list().single()
        val first = approvePendingApproval(approvals, held.id, registry)
        val second = approvePendingApproval(approvals, held.id, registry)
        assertTrue(first is PendingApprovalOutcome.Executed)
        assertTrue(second is PendingApprovalOutcome.AlreadyHandled)
        assertEquals(1, gmail.sentRaws.size)
    }

    // --- Grant validation: implausible addresses never persist. ---

    @Test
    fun implausibleGrantIsRefused() {
        val consents = InMemoryScheduledConsentStore()
        assertEquals(null, grantScheduledSendConsent(consents, "sched_1", setOf("not-an-email"), "Hi", "hello"))
        assertEquals(null, grantScheduledSendConsent(consents, "sched_1", setOf("  "), "Hi", "hello"))
        assertEquals(null, grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "", "hello"))
        assertEquals(null, grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "  "))
        assertTrue(consents.list().isEmpty())
    }

    @Test
    fun recipientMatchIsCaseInsensitiveAndTrimmed() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("Friend@Work.com "), "Hi", "hello")
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(sendCall("  friend@work.COM")), null), answer("sent")))
        val approvals = InMemoryPendingApprovalStore()
        runScheduledRoutine(
            Schedule("sched_1", "send hi", 9, 0),
            1_000L,
            llm,
            fullRegistry(gmail),
            InMemoryScheduleRunStore(),
            approvals,
            consents = consents,
            nowMs = { 1_050L },
        )
        assertEquals(1, gmail.sentRaws.size)
        assertTrue(approvals.list().isEmpty())
    }

    // --- Cron and timezone edges: consent follows the owning id, not the instant. ---

    @Test
    fun consentSurvivesTimezoneRearmForSameSchedule() {
        val zoneKolkata = ZoneId.of("Asia/Kolkata")
        val zoneNewYork = ZoneId.of("America/New_York")
        val schedule = Schedule("sched_1", "send hi", 9, 0)
        val now = ZonedDateTime.of(2026, 11, 23, 3, 30, 0, 0, ZoneId.of("UTC"))
        val fireKolkata = nextFireTime(schedule, zoneKolkata, now)
        val fireNewYork = nextFireTime(schedule, zoneNewYork, now)
        assertTrue(fireKolkata != null && fireNewYork != null)
        // Same wall-clock 9:00 in each zone, different instants.
        assertEquals(9, fireKolkata!!.hour)
        assertEquals(9, fireNewYork!!.hour)
        assertFalse(fireKolkata.toInstant() == fireNewYork.toInstant())
        // Consent is keyed by schedule id, so either fire time still matches.
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        assertTrue(consents.find("sched_1") != null)
        assertTrue(consentMatches(consents.find("sched_1")!!, mapOf("to" to "friend@work.com", "subject" to "Hi", "body" to "hello")))
    }

    @Test
    fun emptyDaysNeverFiresAndConsentNeverRuns() {
        val schedule = Schedule("sched_1", "send hi", 9, 0, days = emptySet())
        val now = ZonedDateTime.of(2026, 11, 23, 0, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertEquals(null, nextFireTime(schedule, ZoneId.of("Asia/Kolkata"), now))
        // No fire time means the routine is never invoked; consent alone sends nothing.
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        assertTrue(gmail.sentRaws.isEmpty())
        assertEquals(1, consents.list().size)
    }

    // --- Expired credentials still post reconnect, never a silent send. ---

    @Test
    fun expiredTokenWithConsentPostsReconnectAndSendsNothing() {
        val gmail = FakeGmail(expired = true)
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(listOf(ToolCall("c1", "search_emails", mapOf("query" to "boss"))), null),
                    answer("could not read mail"),
                ),
            )
        val outcome =
            runScheduledRoutine(
                Schedule("sched_1", "check emails", 9, 0),
                1_000L,
                llm,
                fullRegistry(gmail),
                InMemoryScheduleRunStore(),
                InMemoryPendingApprovalStore(),
                consents = consents,
                nowMs = { 1_050L },
            )
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.AUTH_ERROR, outcome.run.status)
        assertTrue(outcome.notification.title.contains("Reconnect Gmail"))
        assertTrue(gmail.sentRaws.isEmpty())
    }

    // --- Revoked consent holds again. ---

    @Test
    fun revokedConsentHoldsAgain() {
        val gmail = FakeGmail()
        val consents = InMemoryScheduledConsentStore()
        grantScheduledSendConsent(consents, "sched_1", setOf("friend@work.com"), "Hi", "hello")
        consents.revoke("sched_1")
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(sendCall("friend@work.com")), null), answer("held")))
        val approvals = InMemoryPendingApprovalStore()
        runScheduledRoutine(
            Schedule("sched_1", "send hi", 9, 0),
            1_000L,
            llm,
            fullRegistry(gmail),
            InMemoryScheduleRunStore(),
            approvals,
            consents = consents,
            nowMs = { 1_050L },
        )
        assertTrue(gmail.sentRaws.isEmpty())
        assertEquals(1, approvals.list().size)
    }
}
