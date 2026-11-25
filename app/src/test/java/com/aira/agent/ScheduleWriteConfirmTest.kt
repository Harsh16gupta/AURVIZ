package com.aira.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #69 (spec #65 T4): Schedule writes through the Confirmation
// judge. create_schedule and cancel_schedule are gated like send_email
// (ADR-0003): approve arms/disarms, reject and timeout leave the store
// untouched. Voice Command carries identical guarantees because the
// transcript enters the same command path (AgentNav routes it to
// sendCommand); these tests pin that the resolved voice text drives the
// same gated outcomes as the typed literal. Seams: ToolRegistry.
// executeGated over InMemoryScheduleStore, AgentLoop with a scripted
// confirmer. No Android framework, no real alarms.
class ScheduleWriteConfirmTest {

    private fun registry(store: InMemoryScheduleStore = InMemoryScheduleStore()): ToolRegistry =
        defaultToolRegistry(scheduleStore = store)

    private fun createArgs(task: String = "check emails"): Map<String, Any?> =
        mapOf("task" to task, "when" to "at 9am")

    private fun createApproved(store: InMemoryScheduleStore, task: String = "check emails"): String {
        val gated =
            registry(store).executeGated(ToolCall("c1", "create_schedule", createArgs(task))) {
                ApprovalDecision.Approved
            }
        assertTrue(gated.result is ToolResult.Success)
        return ((gated.result as ToolResult.Success).data as Map<*, *>)["id"] as String
    }

    @Test
    fun createRejectArmsNothing() {
        val store = InMemoryScheduleStore()
        val gated =
            registry(store).executeGated(ToolCall("c1", "create_schedule", createArgs())) {
                ApprovalDecision.Rejected
            }
        assertTrue(gated.result is ToolResult.Success)
        assertEquals("cancelled_by_user", ((gated.result as ToolResult.Success).data as Map<*, *>)["status"])
        assertFalse(gated.approved)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun createTimeoutArmsNothing() {
        val store = InMemoryScheduleStore()
        val gated =
            registry(store).executeGated(ToolCall("c1", "create_schedule", createArgs())) {
                ApprovalDecision.TimedOut
            }
        assertTrue(gated.result is ToolResult.Success)
        assertFalse(gated.approved)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun cancelRejectLeavesScheduleArmed() {
        val store = InMemoryScheduleStore()
        val id = createApproved(store)
        val gated =
            registry(store).executeGated(ToolCall("c2", "cancel_schedule", mapOf("schedule_id" to id))) {
                ApprovalDecision.Rejected
            }
        assertFalse(gated.approved)
        assertEquals(1, store.list().size)
        assertEquals(id, store.list().single().id)
    }

    @Test
    fun cancelTimeoutLeavesScheduleArmed() {
        val store = InMemoryScheduleStore()
        val id = createApproved(store)
        val gated =
            registry(store).executeGated(ToolCall("c2", "cancel_schedule", mapOf("schedule_id" to id))) {
                ApprovalDecision.TimedOut
            }
        assertFalse(gated.approved)
        assertEquals(1, store.list().size)
        assertEquals(id, store.list().single().id)
    }

    @Test
    fun cancelApproveDisarms() {
        val store = InMemoryScheduleStore()
        val id = createApproved(store)
        val gated =
            registry(store).executeGated(ToolCall("c2", "cancel_schedule", mapOf("schedule_id" to id))) {
                ApprovalDecision.Approved
            }
        assertTrue(gated.result is ToolResult.Success)
        assertTrue(gated.approved)
        assertTrue(store.list().isEmpty())
    }

    private fun scriptedLoop(
        store: InMemoryScheduleStore,
        command: String,
        confirm: (ToolCall) -> ApprovalDecision,
    ): Pair<AgentLoopResult, List<AgentEvent>> {
        // Seed the id the scripted cancel targets: create it through the
        // same gated path so the run mirrors a real arm-then-disarm.
        createApproved(store)
        val seeded = store.list().single()
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(listOf(ToolCall("c1", "create_schedule", createArgs("second"))), null),
                    LlmResult(listOf(ToolCall("c2", "cancel_schedule", mapOf("schedule_id" to seeded.id))), null),
                    LlmResult(emptyList(), "done"),
                ),
            )
        val events = mutableListOf<AgentEvent>()
        val result =
            AgentLoop(llm, registry(store), onEvent = events::add, confirm = confirm)
                .run(command)
        return result to events
    }

    @Test
    fun typedCommandRaisesApprovalForCreateAndCancel() {
        val store = InMemoryScheduleStore()
        val (result, events) = scriptedLoop(store, "manage my schedule", { ApprovalDecision.Approved })
        assertTrue(result is AgentLoopResult.Answered)
        assertEquals(2, events.count { it is AgentEvent.NeedsApproval })
        assertTrue(result.steps.all { it.approved })
    }

    @Test
    fun voiceTranscriptDrivesIdenticalGatedOutcomes() {
        val voiceStore = InMemoryScheduleStore()
        val typedStore = InMemoryScheduleStore()
        val voiceCommand = resolveCommandInput("manage my schedule", "something else")
        val (voiceResult, voiceEvents) = scriptedLoop(voiceStore, voiceCommand, { ApprovalDecision.Approved })
        val (typedResult, typedEvents) =
            scriptedLoop(typedStore, "manage my schedule", { ApprovalDecision.Approved })
        assertTrue(voiceResult is AgentLoopResult.Answered)
        assertEquals(
            typedEvents.count { it is AgentEvent.NeedsApproval },
            voiceEvents.count { it is AgentEvent.NeedsApproval },
        )
        assertEquals(typedResult.steps.map { it.approved }, voiceResult.steps.map { it.approved })
        assertEquals(typedStore.list().size, voiceStore.list().size)
    }

    @Test
    fun voiceRejectDisarmsNothing() {
        val store = InMemoryScheduleStore()
        val command = resolveCommandInput("manage my schedule", "something else")
        val (result, events) = scriptedLoop(store, command, { ApprovalDecision.Rejected })
        assertTrue(result is AgentLoopResult.Answered)
        assertEquals(2, events.count { it is AgentEvent.NeedsApproval })
        assertTrue(result.steps.none { it.approved })
        // The seeded arm survives; the scripted second create never armed.
        assertEquals(1, store.list().size)
    }
}
