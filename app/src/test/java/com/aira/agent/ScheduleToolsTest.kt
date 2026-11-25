package com.aira.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 73 (part of #71): Schedule adapter pin. The adapter owns the three
// Schedule Tool declarations and registers them into the validate-and-gate
// kernel; behavior is exercised through the kernel seam (execute,
// executeGated, schema view), never by file location. Fake Schedule store
// only.
class ScheduleToolsTest {

    private fun kernelWithSchedules(store: ScheduleStore?): ToolRegistry =
        ToolRegistry().apply {
            for (tool in scheduleTools(store)) register(tool)
        }

    @Test
    fun adapterRegistersCreateListAndCancelIntoTheKernel() {
        val kernel = kernelWithSchedules(InMemoryScheduleStore())
        assertEquals(setOf("create_schedule", "list_schedules", "cancel_schedule"), kernel.names())
    }

    @Test
    fun createAndCancelStayGatedMediumRiskListStaysUngatedLowRisk() {
        val kernel = kernelWithSchedules(InMemoryScheduleStore())
        val create = kernel.find("create_schedule")!!
        assertEquals(RiskLevel.MEDIUM, create.riskLevel)
        assertTrue(create.requiresConfirmation)
        val list = kernel.find("list_schedules")!!
        assertEquals(RiskLevel.LOW, list.riskLevel)
        assertFalse(list.requiresConfirmation)
        val cancel = kernel.find("cancel_schedule")!!
        assertEquals(RiskLevel.MEDIUM, cancel.riskLevel)
        assertTrue(cancel.requiresConfirmation)
    }

    @Test
    fun crudRoundTripBehavesThroughTheKernelSeam() {
        val store = InMemoryScheduleStore()
        val kernel = kernelWithSchedules(store)
        val approve: (ToolCall) -> ApprovalDecision = { ApprovalDecision.Approved }

        val created =
            kernel.executeGated(
                ToolCall("c1", "create_schedule", mapOf("task" to "check emails", "when" to "at 9am")),
                confirm = approve,
            )
        assertTrue(created.result is ToolResult.Success)
        val id = ((created.result as ToolResult.Success).data as Map<*, *>)["id"] as String

        val listed = kernel.execute("list_schedules", emptyMap())
        assertTrue(listed is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val rows = (listed as ToolResult.Success).data as List<Map<String, Any?>>
        assertEquals(1, rows.size)
        assertEquals(id, rows[0]["id"])

        val cancelled =
            kernel.executeGated(
                ToolCall("c2", "cancel_schedule", mapOf("schedule_id" to id)),
                confirm = approve,
            )
        assertTrue(cancelled.result is ToolResult.Success)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun createAndCancelStillRequirePerCallConfirmation() {
        val store = InMemoryScheduleStore()
        val kernel = kernelWithSchedules(store)
        val createArgs = mapOf("task" to "check emails", "when" to "at 9am")

        // Ungated path refuses without touching the store.
        val refused = kernel.execute("create_schedule", createArgs)
        assertTrue(refused is ToolResult.Failure)
        assertFalse((refused as ToolResult.Failure).retryable)
        assertTrue(store.list().isEmpty())
        val refusedCancel = kernel.execute("cancel_schedule", mapOf("schedule_id" to "sched_1"))
        assertTrue(refusedCancel is ToolResult.Failure)

        // Reject disarms nothing.
        val rejected =
            kernel.executeGated(ToolCall("c1", "create_schedule", createArgs)) { ApprovalDecision.Rejected }
        assertTrue(rejected.result is ToolResult.Success)
        assertFalse(rejected.approved)
        assertTrue(store.list().isEmpty())

        // Timeout disarms nothing.
        val timedOut =
            kernel.executeGated(ToolCall("c1b", "create_schedule", createArgs)) { ApprovalDecision.TimedOut }
        assertTrue(timedOut.result is ToolResult.Success)
        assertFalse(timedOut.approved)
        assertTrue(store.list().isEmpty())

        // Approve arms exactly one routine.
        val approved =
            kernel.executeGated(ToolCall("c2", "create_schedule", createArgs)) { ApprovalDecision.Approved }
        assertTrue(approved.result is ToolResult.Success)
        assertTrue(approved.approved)
        assertEquals(1, store.list().size)
        val id = store.list().single().id

        // Cancel reject leaves the routine armed.
        val cancelRejected =
            kernel.executeGated(ToolCall("c3", "cancel_schedule", mapOf("schedule_id" to id))) {
                ApprovalDecision.Rejected
            }
        assertFalse(cancelRejected.approved)
        assertEquals(1, store.list().size)

        // Cancel approve disarms.
        val cancelApproved =
            kernel.executeGated(ToolCall("c4", "cancel_schedule", mapOf("schedule_id" to id))) {
                ApprovalDecision.Approved
            }
        assertTrue(cancelApproved.result is ToolResult.Success)
        assertTrue(cancelApproved.approved)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun cancelUnknownIdFailsWithGuidanceToList() {
        val kernel = kernelWithSchedules(InMemoryScheduleStore())
        val gated =
            kernel.executeGated(
                ToolCall("c1", "cancel_schedule", mapOf("schedule_id" to "sched_999")),
                confirm = { ApprovalDecision.Approved },
            )
        assertTrue(gated.result is ToolResult.Failure)
        val failure = gated.result as ToolResult.Failure
        assertTrue(failure.error.contains("sched_999"))
        assertTrue(failure.error.contains("list_schedules"))
        assertTrue(failure.retryable)
    }

    @Test
    fun missingStoreStillFailsClosed() {
        val kernel = kernelWithSchedules(null)
        val created =
            kernel.executeGated(
                ToolCall("c1", "create_schedule", mapOf("task" to "check emails", "when" to "at 9am")),
                confirm = { ApprovalDecision.Approved },
            )
        assertTrue(created.result is ToolResult.Failure)
        assertFalse((created.result as ToolResult.Failure).retryable)
        val listed = kernel.execute("list_schedules", emptyMap())
        assertTrue(listed is ToolResult.Failure)
        assertFalse((listed as ToolResult.Failure).retryable)
        val cancelled =
            kernel.executeGated(
                ToolCall("c2", "cancel_schedule", mapOf("schedule_id" to "sched_1")),
                confirm = { ApprovalDecision.Approved },
            )
        assertTrue(cancelled.result is ToolResult.Failure)
        assertFalse((cancelled.result as ToolResult.Failure).retryable)
    }

    @Test
    fun schemaViewAdvertisesAllThreeTools() {
        val names =
            kernelWithSchedules(InMemoryScheduleStore()).toolSchemas().let { schemas ->
                (0 until schemas.length())
                    .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
                    .toSet()
            }
        assertEquals(setOf("create_schedule", "list_schedules", "cancel_schedule"), names)
    }
}
