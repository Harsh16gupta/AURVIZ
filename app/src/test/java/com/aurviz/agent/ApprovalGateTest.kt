package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// P0: the Confirmation card approves exactly one Tool call in exactly one
// Task Execution (GLOSSARY Confirmation, ADR-0003). A replaceable queue
// let approval of card A release a different call in run B, and a late
// double-tap queued a decision for the next call.
class ApprovalGateTest {

    @Test
    fun staleCallbackForOldCallIsIgnored() {
        val gate = ApprovalGate()
        val run = gate.startRun()!!
        val first = gate.openGate(run, "call_A")!!
        // Second gated call in the same run replaces the card.
        val second = gate.openGate(run, "call_B")!!
        assertTrue(gate.resolve(run, "call_A", ApprovalDecision.Approved).not())
        assertTrue(first.isEmpty())
        assertTrue(gate.resolve(run, "call_B", ApprovalDecision.Approved))
        assertEquals(ApprovalDecision.Approved, second.poll(1, TimeUnit.SECONDS))
    }

    @Test
    fun decisionFromOldRunCannotReleaseNewRun() {
        val gate = ApprovalGate()
        val run1 = gate.startRun()!!
        gate.openGate(run1, "call_A")
        gate.finishRun(run1)
        val run2 = gate.startRun()!!
        val queue2 = gate.openGate(run2, "call_A")!!
        // Stale run id never matches the open gate.
        assertFalse(gate.resolve(run1, "call_A", ApprovalDecision.Approved))
        assertTrue(queue2.isEmpty())
        assertTrue(gate.resolve(run2, "call_A", ApprovalDecision.Approved))
        assertEquals(ApprovalDecision.Approved, queue2.poll(1, TimeUnit.SECONDS))
    }

    @Test
    fun doubleTapDoesNotLeakIntoNextCall() {
        val gate = ApprovalGate()
        val run = gate.startRun()!!
        val first = gate.openGate(run, "call_1")!!
        assertTrue(gate.resolve(run, "call_1", ApprovalDecision.Approved))
        // Late second tap on the same card is dropped (one-shot).
        assertFalse(gate.resolve(run, "call_1", ApprovalDecision.Approved))
        assertEquals(ApprovalDecision.Approved, first.poll(1, TimeUnit.SECONDS))
        // Next gated call opens a fresh empty queue — no queued decision.
        val second = gate.openGate(run, "call_2")!!
        assertTrue(second.isEmpty())
        assertNull(second.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun secondRunBlockedWhileFirstRuns() {
        val gate = ApprovalGate()
        assertNotNull(gate.startRun())
        assertTrue(gate.isRunning())
        assertNull(gate.startRun())
    }

    @Test
    fun defaultConfirmerIsFailClosed() {
        val llm = FakeLlmProvider(
            listOf(
                LlmResult(
                    toolCalls = listOf(ToolCall("c1", "create_schedule", mapOf("task" to "x", "when" to "at 9am"))),
                    content = null,
                ),
                LlmResult(toolCalls = emptyList(), content = "done"),
            ),
        )
        val store = InMemoryScheduleStore()
        val result = AgentLoop(llm, defaultToolRegistry(scheduleStore = store)).run("schedule x")
        assertTrue(result is AgentLoopResult.Answered)
        // Fail-closed: nothing executed without an explicit approve.
        assertEquals(0, store.list().size)
        assertFalse(result.steps[0].approved)
    }

    @Test
    fun twoConsecutiveSensitiveCallsNeedTwoApprovals() {
        val llm = FakeLlmProvider(
            listOf(
                LlmResult(
                    toolCalls = listOf(ToolCall("c1", "create_schedule", mapOf("task" to "a", "when" to "at 9am"))),
                    content = null,
                ),
                LlmResult(
                    toolCalls = listOf(ToolCall("c2", "create_schedule", mapOf("task" to "b", "when" to "at 10am"))),
                    content = null,
                ),
                LlmResult(toolCalls = emptyList(), content = "done"),
            ),
        )
        val store = InMemoryScheduleStore()
        val seen = mutableListOf<String>()
        val errors = AtomicReference<Throwable?>(null)
        val loop = AgentLoop(
            llm,
            defaultToolRegistry(scheduleStore = store),
            confirm = { call ->
                seen += call.id.orEmpty()
                // Approve only the second call; reject the first.
                if (call.id == "c2") ApprovalDecision.Approved else ApprovalDecision.Rejected
            },
        )
        val done = CountDownLatch(1)
        var result: AgentLoopResult? = null
        Thread {
            try {
                result = loop.run("two schedules")
            } catch (t: Throwable) {
                errors.set(t)
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNull(errors.get())
        assertEquals(listOf("c1", "c2"), seen)
        assertEquals(2, result!!.steps.size)
        assertFalse(result!!.steps[0].approved)
        assertTrue(result!!.steps[1].approved)
        assertEquals(1, store.list().size)
    }
}
