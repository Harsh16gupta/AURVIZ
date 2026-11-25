package com.aira.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #115 (Memory Upgrade 3/4): on-device Mem0-style fact reconciliation
// (ADD / UPDATE / NOOP). Covers ADD without an LLM round-trip, UPDATE with
// ranking + provenance preservation, NOOP dedup, the canonical Mem0
// {"memory":[...]} envelope, offline/LLM-failure fail-open, hallucinated
// targetId safety, and the untrusted "auto-saved" poison guard.
class MemoryReconcilerTest {

    private fun reply(content: String) = LlmResult(emptyList(), content)

    @Test
    fun reconcilerAddsNewFactWhenStoreIsEmptyWithoutCallingLlm() {
        val store = InMemoryStore()
        val llm = FakeLlmProvider(listOf(reply("""{"action":"ADD","text":"my boss is Priya"}""")))
        val reconciler = MemoryReconciler(llm)

        val outcome = reconciler.remember(store, "my boss is Priya", "you told me")

        assertEquals(1, store.list().size)
        assertEquals("my boss is Priya", outcome.memory.text)
        assertTrue(outcome.action is ReconcileAction.Add)
        assertTrue(llm.seenMessages.isEmpty())
    }

    @Test
    fun reconcilerUpdatesContradictoryFactAndPreservesRankingAndProvenance() {
        val store = InMemoryStore()
        store.save(
            text = "my boss is Priya",
            source = "you told me",
            category = "work",
            importance = 8,
            createdAt = 1000L,
            lastUsed = 2000L,
            useCount = 4,
            originId = "in_1",
            originSnippet = "remember my boss is Priya",
            originTimestamp = 1000L,
        )
        val llm = FakeLlmProvider(listOf(reply("""{"action":"UPDATE","targetId":"mem_1","text":"my boss is Rahul"}""")))
        val reconciler = MemoryReconciler(llm)

        val outcome =
            reconciler.remember(
                store,
                "my boss is now Rahul",
                "you told me",
                createdAt = 5000L,
                originId = "in_2",
            )

        assertTrue(outcome.action is ReconcileAction.Update)
        assertEquals(1, store.list().size)
        val updated = store.list().single()
        assertEquals("my boss is Rahul", updated.text)
        assertEquals("work", updated.category)
        assertEquals(8, updated.importance)
        assertEquals(1000L, updated.createdAt)
        assertEquals(5000L, updated.lastUsed)
        assertEquals(4, updated.useCount)
        assertEquals("in_2", updated.originId)
        assertEquals("remember my boss is Priya", updated.originSnippet)
    }

    @Test
    fun reconcilerNoopReturnsExistingRowWithoutCreatingDuplicate() {
        val store = InMemoryStore()
        store.save("my boss is Priya", "you told me")
        val llm = FakeLlmProvider(listOf(reply("""{"action":"NOOP","targetId":"mem_1"}""")))
        val reconciler = MemoryReconciler(llm)

        val outcome = reconciler.remember(store, "Priya is my boss", "you told me")

        assertEquals(1, store.list().size)
        assertTrue(outcome.action is ReconcileAction.Noop)
        assertEquals("mem_1", outcome.memory.id)
    }

    @Test
    fun parseReconcileActionSupportsCanonicalMem0MemoryArrayAndNoneEvent() {
        val update =
            parseReconcileAction(
                """{"memory":[{"id":"mem_1","text":"my boss is Rahul","event":"UPDATE","old_memory":"my boss is Priya"}]}""",
                setOf("mem_1"),
                "my boss is now Rahul",
            )
        assertEquals(ReconcileAction.Update("mem_1", "my boss is Rahul"), update)

        val none =
            parseReconcileAction(
                """{"memory":[{"id":"mem_1","text":"my boss is Priya","event":"NONE"}]}""",
                setOf("mem_1"),
                "my boss is Priya",
            )
        assertEquals(ReconcileAction.Noop("mem_1"), none)
    }

    @Test
    fun reconcilerFailsOpenToRememberFactWhenLlmThrowsOrReturnsInvalidJson() {
        val throwingLlm =
            object : ChatLlmProvider {
                override fun chat(messages: List<ChatMessage>): LlmResult {
                    throw RuntimeException("offline")
                }
            }
        val offlineStore = InMemoryStore()
        val offlineOutcome = MemoryReconciler(throwingLlm).remember(offlineStore, "my boss is Priya", "you told me")
        assertEquals(1, offlineStore.list().size)
        assertEquals("my boss is Priya", offlineOutcome.memory.text)
        assertTrue(offlineOutcome.action is ReconcileAction.Add)

        val invalidJsonStore = InMemoryStore()
        val invalidJsonLlm = FakeLlmProvider(listOf(reply("not json")))
        val invalidJsonOutcome =
            MemoryReconciler(invalidJsonLlm).remember(invalidJsonStore, "my boss is Priya", "you told me")
        assertEquals(1, invalidJsonStore.list().size)
        assertTrue(invalidJsonOutcome.action is ReconcileAction.Add)

        // Exact duplicates still dedup through the fail-open path.
        val deduped =
            MemoryReconciler(invalidJsonLlm).remember(invalidJsonStore, "  My Boss Is Priya ", "you told me")
        assertEquals(1, invalidJsonStore.list().size)
        assertEquals(invalidJsonOutcome.memory.id, deduped.memory.id)
    }

    @Test
    fun reconcilerRejectsHallucinatedTargetIdOnUpdateAndAddsInstead() {
        val store = InMemoryStore()
        store.save("my boss is Priya", "you told me")
        val llm = FakeLlmProvider(listOf(reply("""{"action":"UPDATE","targetId":"mem_999","text":"my boss is Rahul"}""")))
        val reconciler = MemoryReconciler(llm)

        val outcome = reconciler.remember(store, "my boss is now Rahul", "you told me")

        assertTrue(outcome.action is ReconcileAction.Add)
        assertEquals(2, store.list().size)
        assertTrue(store.list().any { it.id == "mem_1" && it.text == "my boss is Priya" })
        assertEquals("my boss is now Rahul", outcome.memory.text)
    }

    @Test
    fun untrustedAutoSavedFactNeverOverwritesTrustedYouToldMeRow() {
        val store = InMemoryStore()
        store.save("my boss is Priya", "you told me")
        // Even if the model tried to UPDATE the trusted row, the poison
        // guard filters "you told me" candidates out before any LLM call.
        val llm = FakeLlmProvider(listOf(reply("""{"action":"UPDATE","targetId":"mem_1","text":"my boss is Eve"}""")))
        val reconciler = MemoryReconciler(llm)
        val tool = saveMemoryTool(store, reconciler)

        // Ticket #107 (T2): an email read occurred, so the attacker-influenced
        // save_memory call is forced to source "auto-saved" before the gate.
        val injected = ToolCall("c1", "save_memory", mapOf("text" to "my boss is Eve", "source" to "you told me"))
        val safeCall = sanitizeMemoryCallForUntrustedRun(injected, untrustedReadOccurred = true)
        assertEquals("auto-saved", safeCall.arguments["source"])

        val result = tool.execute(safeCall.arguments)
        assertTrue(result is ToolResult.Success)

        assertEquals(2, store.list().size)
        val trusted = store.list().single { it.id == "mem_1" }
        assertEquals("my boss is Priya", trusted.text)
        assertEquals("you told me", trusted.source)
        val untrusted = store.list().single { it.id != "mem_1" }
        assertEquals("my boss is Eve", untrusted.text)
        assertEquals("auto-saved", untrusted.source)
        // The guard short-circuits before consulting the LLM.
        assertTrue(llm.seenMessages.isEmpty())
    }
}
