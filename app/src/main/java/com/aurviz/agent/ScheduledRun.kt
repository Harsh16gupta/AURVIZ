package com.aurviz.agent

// Ticket 17/24: scheduled-run deferral seam (ADR-0002, gaps §3E). A
// scheduled run has no user present, so its Confirmation never approves:
// the loop runs on the read-only allowlist (GLOSSARY Schedule — the
// scheduled registry never holds the send tool) and any sensitive attempt
// the model still makes (send_email, schedule writes) becomes a
// PendingApproval row for in-app approval instead of executing. The
// transport is untouched on every path. The Worker persists these rows to
// Room and posts the notification on the device; the in-memory store
// below is the JVM-testable seam.
data class PendingApproval(
    val scheduleId: String,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val reason: String,
)

interface PendingApprovalStore {
    fun save(approval: PendingApproval): PendingApproval
    fun list(): List<PendingApproval>
}

class InMemoryPendingApprovalStore : PendingApprovalStore {
    private val items = mutableListOf<PendingApproval>()
    override fun save(approval: PendingApproval): PendingApproval {
        items += approval
        return approval
    }
    override fun list(): List<PendingApproval> = items.toList()
}

// Tool names that never run unattended. Attempts reach this set either as
// gate-refusals (registered but gated) or as unknown-tool failures (absent
// from the read-only registry) — both without touching the transport.
val SCHEDULED_SENSITIVE_TOOLS = setOf("send_email", "create_schedule", "cancel_schedule")

// One unattended run: the loop with a never-approving Confirmation hook,
// then every sensitive attempt the model made is held as a PendingApproval
// row. Reads run normally; sends and schedule writes never execute.
fun runScheduled(
    scheduleId: String,
    instruction: String,
    llm: ChatLlmProvider,
    registry: ToolRegistry,
    pending: PendingApprovalStore,
    memories: List<Memory> = emptyList(),
    onEvent: (AgentEvent) -> Unit = {},
): AgentLoopResult {
    val result =
        AgentLoop(llm, registry, onEvent = onEvent, confirm = { ApprovalDecision.Rejected })
            .run(instruction, memories)
    for (step in result.steps) {
        if (step.call.name in SCHEDULED_SENSITIVE_TOOLS) {
            pending.save(
                PendingApproval(
                    scheduleId = scheduleId,
                    toolName = step.call.name,
                    arguments = step.call.arguments,
                    reason = "scheduled run: sensitive tool held for user approval, never executed",
                ),
            )
        }
    }
    return result
}
