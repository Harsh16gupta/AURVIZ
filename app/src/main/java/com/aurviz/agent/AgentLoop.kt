package com.aurviz.agent

import org.json.JSONObject

// Ticket 02/24: hand-rolled agent loop (ADR-0005). The Agent keeps a
// messages list, calls the LLM, executes each Tool call through the
// ToolRegistry, appends role:"tool" results matched by tool_call_id, and
// repeats until a plain answer. Per-step (90s) and total (5min) timeouts
// abort to Failed with the partial steps kept (ticket 10/24); an LLM
// transport failure aborts the same way instead of crashing. Tool errors arrive as structured
// {ok:false, error, retryable} results so the model can recover instead
// of crashing. The loop emits AgentEvents into the existing timeline UI
// path (AgentLoopResult.toTimeline reuses buildTimeline).
//
// Boundaries (AGENTS.md): the Agent orchestrates; Tool implementations
// stay in the registry. The Confirmation gate for requiresConfirmation
// Tools lives in the registry (executeGated) and the loop routes every
// call through it, suspending on the confirm hook (approve, edited-
// approve, reject, or timeout); real UI suspension wires in with ticket 24.

// One turn of the conversation. Roles: "user" for the command,
// "assistant" for LLM turns (carrying its Tool calls), "tool" for one
// Tool result whose toolCallId matches the assistant ToolCall id.
data class ChatMessage(
    val role: String,
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

// Conversational LLM seam for the loop. Single-shot LlmProvider.plan is
// the planner seam; the loop needs the full messages list every turn.
interface ChatLlmProvider {
    fun chat(messages: List<ChatMessage>): LlmResult
}

// Per-sensitive-call Confirmation decision (GLOSSARY). Reject and timeout
// return {status:"cancelled_by_user"} to the model; the Tool never runs.
sealed interface ApprovalDecision {
    data object Approved : ApprovalDecision

    data class ApprovedWithEdit(val args: Map<String, Any?>) : ApprovalDecision

    data object Rejected : ApprovalDecision

    data object TimedOut : ApprovalDecision
}

// Loop lifecycle events. The UI renders these into ChatTimeline rows:
// StepStarted/ToolRunning flip a row to RUNNING, StepDone flips it to
// DONE, NeedsApproval raises the Confirmation card, Failed adds the
// Error row. Terminal success is the plain answer itself.
sealed interface AgentEvent {
    data class StepStarted(val stepIndex: Int) : AgentEvent

    data class ToolRunning(val call: ToolCall) : AgentEvent

    data class NeedsApproval(val call: ToolCall) : AgentEvent

    data class StepDone(val call: ToolCall, val result: ToolResult) : AgentEvent

    data class Failed(val error: String) : AgentEvent
}

// One executed Tool: the call the model made (with the approved or
// edited args) and its structured result.
data class ExecutedStep(
    val call: ToolCall,
    val result: ToolResult,
    val approved: Boolean,
)

// Terminal Task Execution outcome (GLOSSARY): the Plan the loop actually
// made, recorded with results, plus the full messages list.
sealed interface AgentLoopResult {
    val steps: List<ExecutedStep>
    val messages: List<ChatMessage>

    data class Answered(
        val answer: String,
        override val steps: List<ExecutedStep>,
        override val messages: List<ChatMessage>,
    ) : AgentLoopResult

    data class Failed(
        val error: String,
        override val steps: List<ExecutedStep>,
        override val messages: List<ChatMessage>,
    ) : AgentLoopResult
}

class AgentLoop(
    private val llm: ChatLlmProvider,
    private val registry: ToolRegistry,
    private val maxSteps: Int = MAX_STEPS,
    private val onEvent: (AgentEvent) -> Unit = {},
    private val confirm: (ToolCall) -> ApprovalDecision = { ApprovalDecision.Rejected },
    // Ticket 10/24: per-step and total timeouts (ADR-0005, gaps §3H).
    // The blocking calls underneath are already bounded by the OkHttp
    // connect/read timeouts; the loop turns an overrun or a transport
    // failure into Failed with the partial steps kept. nowMs is
    // injectable so the abort paths run deterministically in tests.
    private val stepTimeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_STEPS = 8
        const val DEFAULT_STEP_TIMEOUT_MS = 90_000L
        const val DEFAULT_TOTAL_TIMEOUT_MS = 300_000L
    }

    fun run(userText: String, memories: List<Memory> = emptyList()): AgentLoopResult {
        // Ticket 16/29: the system prompt opens the conversation (tool
        // content is data, never instructions) and memories ride in its
        // delimited section. The user message carries the bare command —
        // no memory concatenation on this path (ADR-0005).
        val messages =
            mutableListOf(
                ChatMessage(role = "system", content = buildSystemPrompt(memories)),
                ChatMessage(role = "user", content = userText),
            )
        val steps = mutableListOf<ExecutedStep>()
        val startMs = nowMs()
        fun failed(error: String): AgentLoopResult.Failed {
            onEvent(AgentEvent.Failed(error))
            return AgentLoopResult.Failed(error, steps.toList(), messages.toList())
        }
        repeat(maxSteps) { stepIndex ->
            if (nowMs() - startMs >= totalTimeoutMs) {
                return failed("total timeout after ${totalTimeoutMs}ms at step $stepIndex")
            }
            onEvent(AgentEvent.StepStarted(stepIndex))
            val stepStartMs = nowMs()
            val turn =
                try {
                    llm.chat(messages.toList())
                } catch (e: Exception) {
                    return failed("step $stepIndex failed: ${e.message ?: e.javaClass.simpleName}")
                }
            if (nowMs() - stepStartMs >= stepTimeoutMs) {
                return failed("step $stepIndex timed out after ${stepTimeoutMs}ms")
            }
            if (turn.toolCalls.isEmpty()) {
                return AgentLoopResult.Answered(turn.content.orEmpty(), steps.toList(), messages.toList())
            }
            val calls =
                turn.toolCalls.mapIndexed { callIndex, call ->
                    if (call.id != null) call else call.copy(id = "call_s${stepIndex}_$callIndex")
                }
            messages += ChatMessage(role = "assistant", content = turn.content, toolCalls = calls)
            for ((callIndex, call) in calls.withIndex()) {
                // Total-budget check before each subsequent call in the batch:
                // a slow first Tool or Confirmation must not let another call
                // start after the deadline. Partial steps are kept.
                if (callIndex > 0 && nowMs() - startMs >= totalTimeoutMs) {
                    return failed("total timeout after ${totalTimeoutMs}ms at step $stepIndex")
                }
                onEvent(AgentEvent.ToolRunning(call))
                if (registry.requiresConfirmation(call.name)) {
                    onEvent(AgentEvent.NeedsApproval(call))
                }
                val gated = registry.executeGated(call, confirm)
                steps += ExecutedStep(call.copy(arguments = gated.effectiveArgs), gated.result, gated.approved)
                onEvent(AgentEvent.StepDone(call, gated.result))
                messages +=
                    ChatMessage(
                        role = "tool",
                        content = JSONObject(gated.result.toMap()).toString(),
                        toolCallId = call.id,
                    )
            }
        }
        val error = "stopped after $maxSteps steps without an answer"
        onEvent(AgentEvent.Failed(error))
        return AgentLoopResult.Failed(error, steps.toList(), messages.toList())
    }
}

// Existing timeline UI path: render the Task Execution the loop actually
// ran. Answered marks every executed Tool DONE; Failed keeps the partial
// steps DONE and adds the Error tail row via buildTimeline.
fun AgentLoopResult.toTimeline(): List<TimelineStep> {
    val plan = Plan(steps.map { it.call })
    return when (this) {
        is AgentLoopResult.Answered -> buildTimeline(plan, doneCount = steps.size, error = null)
        is AgentLoopResult.Failed -> buildTimeline(plan, doneCount = steps.size, error = error)
    }
}
