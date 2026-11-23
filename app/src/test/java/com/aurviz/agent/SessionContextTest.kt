package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Issue #51: session-scoped conversational context. Turns inside one
// Session share context; a new Session starts cold. Memory (Room) stays
// available to every Session; the transcript below is short-term only.
class SessionContextTest {

    private fun answer(text: String) = LlmResult(toolCalls = emptyList(), content = text)

    @Test
    fun historyCarriedIntoSecondTurn() {
        val llm = FakeLlmProvider(listOf(answer("found Q3 plan")))
        val transcript = mutableListOf<ChatMessage>()
        val first = AgentLoop(llm, ToolRegistry()).run("find Q3 plan")
        appendSessionTurn(transcript, "find Q3 plan", first)

        val llm2 = FakeLlmProvider(listOf(answer("sent")))
        val second =
            AgentLoop(llm2, ToolRegistry()).run(
                "send it to myself",
                accountEmail = "you@gmail.com",
                history = transcript.toList(),
            )
        assertTrue(second is AgentLoopResult.Answered)
        // The follow-up saw the prior turn: system + first user/assistant + new user.
        val seen = llm2.seenMessages.single()
        assertEquals("system", seen.first().role)
        assertTrue(seen.any { it.role == "user" && it.content == "find Q3 plan" })
        assertTrue(seen.any { it.role == "assistant" && it.content == "found Q3 plan" })
        assertEquals("send it to myself", seen.last().content)
    }

    @Test
    fun newSessionStartsCold() {
        // Empty transcript = cold start: only system + fresh user ride.
        val llm = FakeLlmProvider(listOf(answer("hi")))
        AgentLoop(llm, ToolRegistry()).run("hi fresh", history = emptyList())
        val seen = llm.seenMessages.single()
        assertEquals(2, seen.size)
        assertEquals("system", seen[0].role)
        assertEquals("hi fresh", seen[1].content)
    }

    @Test
    fun identityGroundsMyselfAndAsksWhenUnknown() {
        val known = buildSystemPrompt(accountEmail = "you@gmail.com")
        assertTrue(known.contains("you@gmail.com"))
        assertTrue(known.contains("[identity]"))
        val unknown = buildSystemPrompt(accountEmail = null)
        assertTrue(unknown.contains("Do not guess"))
    }

    @Test
    fun compactionKeepsFirstAndRecentDropsMiddle() {
        val history = mutableListOf<ChatMessage>()
        repeat(30) { i ->
            history += ChatMessage(role = "user", content = "q$i")
            history += ChatMessage(role = "assistant", content = "a$i")
        }
        val pruned = pruneSessionHistory(history)
        assertEquals(MAX_SESSION_HISTORY_MESSAGES, pruned.size)
        // First anchor preserved (priority 2), tail preserved (priority 3).
        assertEquals("q0", pruned.first().content)
        assertEquals("a29", pruned.last().content)
    }

    @Test
    fun appendDropsToolBodies() {
        val transcript = mutableListOf<ChatMessage>()
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(
                        toolCalls = listOf(ToolCall("c1", "read_email", mapOf("id" to "1"))),
                        content = null,
                    ),
                    answer("body is secret-body"),
                ),
            )
        val registry =
            ToolRegistry().apply {
                register(
                    ToolDefinition(
                        name = "read_email",
                        description = "r",
                        parameters = org.json.JSONObject().put("type", "object"),
                        riskLevel = RiskLevel.LOW,
                        requiresConfirmation = false,
                        resultType = "email",
                        execute = { ToolResult.Success(mapOf("body" to "secret-body")) },
                    ),
                )
            }
        val result = AgentLoop(llm, registry).run("read mail")
        appendSessionTurn(transcript, "read mail", result)
        // Transcript holds the compact answer, not the raw tool JSON.
        assertTrue(transcript.none { it.role == "tool" })
        assertTrue(transcript.any { it.content == "body is secret-body" })
    }
}
