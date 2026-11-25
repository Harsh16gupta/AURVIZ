package com.aira.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 15/24: blocked scheduled-run writes become PendingApproval rows
// plus a notification, and the user approves or rejects in-app with the
// approval executing exactly once. Seams: PendingApprovalStore.take
// (atomic claim), approvePendingApproval / rejectPendingApproval (gated
// execution), the blocked-run → approve end-to-end path, and the Room
// adapter round-trip. Fake transports only — no test touches real mail.
class PendingApprovalTest {

    private class RecordingGmail : GmailApi {
        val sentRaws = mutableListOf<String>()
        override fun send(rawBase64Url: String) {
            sentRaws += rawBase64Url
        }
        override fun listInbox(): List<EmailSummary> = emptyList()
        override fun searchInbox(query: String, max: Int): List<EmailSummary> = emptyList()
        override fun readMessage(id: String): EmailDetail =
            EmailDetail(id, "a@b.c", "s", "snip", "body", emailSource(id))
    }

    private fun answer(text: String) = LlmResult(toolCalls = emptyList(), content = text)

    private fun sendApproval(
        scheduleId: String = "sched_1",
        to: String = "friend@work.com",
    ) = PendingApproval(
        scheduleId = scheduleId,
        toolName = "send_email",
        arguments = mapOf("to" to to, "subject" to "Hi", "body" to "hello"),
        reason = "scheduled run: sensitive tool held for an in-app Confirmation decision, never executed",
    )

    private fun registry(gmail: RecordingGmail, schedules: InMemoryScheduleStore = InMemoryScheduleStore()) =
        defaultToolRegistry(gmailApi = gmail, scheduleStore = schedules, memoryStore = InMemoryStore())

    // --- Seam 1: every saved row carries a stable id. ---

    @Test
    fun savedApprovalsGetDistinctStableIds() {
        val store = InMemoryPendingApprovalStore()
        val first = store.save(sendApproval())
        val second = store.save(sendApproval())
        assertTrue(first.id.isNotBlank())
        assertTrue(second.id.isNotBlank())
        assertNotEquals(first.id, second.id)
        assertEquals(setOf(first.id, second.id), store.list().map { it.id }.toSet())
    }

    // --- Seam 1b: take() claims atomically; the second take loses. ---

    @Test
    fun takeClaimsRowOnce() {
        val store = InMemoryPendingApprovalStore()
        val saved = store.save(sendApproval())
        val claimed = store.take(saved.id)
        assertEquals("send_email", claimed?.toolName)
        assertEquals("friend@work.com", claimed?.arguments?.get("to"))
        assertTrue(store.take(saved.id) == null)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun takeUnknownIdReturnsNull() {
        assertTrue(InMemoryPendingApprovalStore().take("approval_999") == null)
    }

    // --- Seam 2: approve runs the held call through the gate exactly once. ---

    @Test
    fun approveSendExecutesThroughGateOnce() {
        val gmail = RecordingGmail()
        val store = InMemoryPendingApprovalStore()
        val saved = store.save(sendApproval())
        val outcome = approvePendingApproval(store, saved.id, registry(gmail))
        assertTrue(outcome is PendingApprovalOutcome.Executed)
        assertTrue((outcome as PendingApprovalOutcome.Executed).result is ToolResult.Success)
        assertEquals(1, gmail.sentRaws.size)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun doubleApproveExecutesExactlyOnce() {
        val gmail = RecordingGmail()
        val store = InMemoryPendingApprovalStore()
        val saved = store.save(sendApproval())
        val first = approvePendingApproval(store, saved.id, registry(gmail))
        val second = approvePendingApproval(store, saved.id, registry(gmail))
        assertTrue(first is PendingApprovalOutcome.Executed)
        assertTrue(second is PendingApprovalOutcome.AlreadyHandled)
        assertEquals(1, gmail.sentRaws.size)
    }

    @Test
    fun approveUnknownIdIsAlreadyHandled() {
        val gmail = RecordingGmail()
        val outcome = approvePendingApproval(InMemoryPendingApprovalStore(), "approval_999", registry(gmail))
        assertTrue(outcome is PendingApprovalOutcome.AlreadyHandled)
        assertTrue(gmail.sentRaws.isEmpty())
    }

    // --- Seam 2b: reject never touches the transport and consumes the row. ---

    @Test
    fun rejectNeverExecutesAndConsumesRow() {
        val gmail = RecordingGmail()
        val store = InMemoryPendingApprovalStore()
        val saved = store.save(sendApproval())
        assertTrue(rejectPendingApproval(store, saved.id) is PendingApprovalOutcome.Rejected)
        assertTrue(gmail.sentRaws.isEmpty())
        assertTrue(store.list().isEmpty())
        assertTrue(rejectPendingApproval(store, saved.id) is PendingApprovalOutcome.AlreadyHandled)
    }

    // --- Seam 2c: schedule writes approve through the same claim path. ---

    @Test
    fun approveScheduleWriteCreatesOneScheduleEvenWhenDoubled() {
        val gmail = RecordingGmail()
        val schedules = InMemoryScheduleStore()
        val store = InMemoryPendingApprovalStore()
        val saved =
            store.save(
                PendingApproval(
                    scheduleId = "sched_9",
                    toolName = "create_schedule",
                    arguments = mapOf("task" to "check emails", "when" to "every morning at 9am"),
                    reason = "scheduled run: sensitive tool held for an in-app Confirmation decision, never executed",
                ),
            )
        val first = approvePendingApproval(store, saved.id, registry(gmail, schedules))
        val second = approvePendingApproval(store, saved.id, registry(gmail, schedules))
        assertTrue(first is PendingApprovalOutcome.Executed)
        assertTrue(second is PendingApprovalOutcome.AlreadyHandled)
        assertEquals(1, schedules.list().size)
    }

    // --- Seam 3: blocked run → approve end to end; the held send runs once. ---

    @Test
    fun blockedRunThenApproveSendsOnce() {
        val gmail = RecordingGmail()
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(
                        listOf(
                            ToolCall(
                                "c1",
                                "send_email",
                                mapOf("to" to "friend@work.com", "subject" to "Hi", "body" to "hello"),
                            ),
                        ),
                        null,
                    ),
                    answer("Held for your approval"),
                ),
            )
        val runs = InMemoryScheduleRunStore()
        val approvals = InMemoryPendingApprovalStore()
        val schedule = Schedule("sched_1", "check emails", 9, 0)
        val outcome = runScheduledRoutine(schedule, 1_000L, llm, registry(gmail), runs, approvals, nowMs = { 1_050L })
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(ScheduleRunStatus.BLOCKED_WRITE, outcome.run.status)
        assertTrue(gmail.sentRaws.isEmpty())
        val held = approvals.list().single()
        assertTrue(outcome.notification.text.contains("Confirmation"))

        val approved = approvePendingApproval(approvals, held.id, registry(gmail))
        assertTrue(approved is PendingApprovalOutcome.Executed)
        assertEquals(1, gmail.sentRaws.size)
        assertTrue(approvals.list().isEmpty())
    }

    // --- Seam 3b: the in-app card reuses the approval-card model. ---

    @Test
    fun heldSendRendersExactCardFields() {
        val store = InMemoryPendingApprovalStore()
        val saved = store.save(sendApproval(to = "newperson@work.com"))
        val card = approvalCardFor(
            ToolCall(saved.id, saved.toolName, saved.arguments),
            knownAddresses = setOf("boss@work.com"),
        )!!
        assertEquals(listOf("newperson@work.com"), card.to.map { it.address })
        assertEquals(listOf("newperson@work.com"), card.unseen)
        assertEquals("Hi", card.subject)
        assertEquals("hello", card.body)
    }

    // --- Seam 4: Room adapter round-trips ids and claims. ---

    private class FakeApprovalDao : PendingApprovalDao {
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
    fun roomAdapterRoundTripsIdsAndTakeClaimsOnce() {
        val store = RoomPendingApprovalStore(FakeApprovalDao())
        val saved = store.save(sendApproval())
        assertTrue(saved.id.isNotBlank())
        assertEquals("friend@work.com", store.list().single().arguments["to"])
        val claimed = store.take(saved.id)
        assertEquals(saved.id, claimed?.id)
        assertTrue(store.take(saved.id) == null)
        assertTrue(store.list().isEmpty())
    }
}
