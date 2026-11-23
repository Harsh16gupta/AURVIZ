package com.aurviz.agent

import org.json.JSONObject

// Ticket 17/24: scheduled-run deferral seam (ADR-0002, gaps §3E). A
// scheduled run has no user present, so its Confirmation never approves:
// the loop runs on the read-only allowlist (GLOSSARY Schedule — the
// scheduled registry never holds the send tool) and any sensitive attempt
// the model still makes (send_email, schedule writes) becomes a
// PendingApproval row for in-app approval instead of executing. The
// transport is untouched on every path. The Worker persists these rows to
// Room and posts the notification on the device; the in-memory store
// below is the JVM-testable seam.
//
// Ticket 15/28: the row carries a stable id (Room pid / in-memory
// counter) so the in-app screen can approve or reject one row. take() is
// the atomic claim: it removes and returns the row, or null when another
// approval already claimed it. approvePendingApproval claims first and
// only then runs the held call through the registry's single gated path
// (executeGated as Approved), so a double-approve executes exactly once;
// rejectPendingApproval claims and drops without ever touching the
// transport. Claimed rows never return to the store, so a failed
// execution still counts as handled — nothing runs twice.
data class PendingApproval(
    val scheduleId: String,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val reason: String,
    val id: String = "",
)

interface PendingApprovalStore {
    fun save(approval: PendingApproval): PendingApproval
    fun list(): List<PendingApproval>
    fun take(id: String): PendingApproval?
}

class InMemoryPendingApprovalStore : PendingApprovalStore {
    private val items = mutableMapOf<String, PendingApproval>()
    private var nextId = 0L

    @Synchronized
    override fun save(approval: PendingApproval): PendingApproval {
        nextId += 1
        val saved = approval.copy(id = "approval_$nextId")
        items[saved.id] = saved
        return saved
    }

    @Synchronized
    override fun list(): List<PendingApproval> = items.values.toList()

    @Synchronized
    override fun take(id: String): PendingApproval? = items.remove(id)
}

// One in-app decision on a held write: Executed carries the held call
// plus the gated result (Success or a structured Failure the screen can
// show); Rejected means the row was dropped untouched; AlreadyHandled
// means a previous approve/reject already claimed the row.
sealed interface PendingApprovalOutcome {
    data class Executed(val approval: PendingApproval, val result: ToolResult) : PendingApprovalOutcome
    data object Rejected : PendingApprovalOutcome
    data object AlreadyHandled : PendingApprovalOutcome
}

// In-app approve: claim the row first so a concurrent double-approve
// loses here, before any transport touch; then run the exact held
// arguments through the single gated path as Approved. The deterministic
// policy screen still applies (a Deny comes back as Executed Failure),
// and the registry validates args before anything runs.
fun approvePendingApproval(
    store: PendingApprovalStore,
    id: String,
    registry: ToolRegistry,
): PendingApprovalOutcome {
    val approval = store.take(id) ?: return PendingApprovalOutcome.AlreadyHandled
    val gated =
        registry.executeGated(
            ToolCall(approval.id, approval.toolName, approval.arguments),
        ) { ApprovalDecision.Approved }
    return PendingApprovalOutcome.Executed(approval, gated.result)
}

// In-app reject: claim and drop. The transport is never touched.
fun rejectPendingApproval(
    store: PendingApprovalStore,
    id: String,
): PendingApprovalOutcome =
    if (store.take(id) == null) PendingApprovalOutcome.AlreadyHandled
    else PendingApprovalOutcome.Rejected

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

// --- Ticket 13/24: headless scheduled runs (ADR-0002, gaps §3D). ---
// runScheduled above is the deferral seam; below is the Worker-grade
// runner: the read-only registry view, the schedule_runs idempotency
// contract (scheduleId+fireTime), the headless LLM path, and the
// notification wording. Room adapters live in ScheduleDb.kt; the
// expedited Worker wiring lives in ScheduleWork.kt. Pure logic,
// JVM-testable throughout.

// ADR-0002: scheduled runs see reads only. A scheduled send_email or
// schedule/memory write is never executed: the loop records a
// PendingApproval row and the user approves in-app.
// getImportantEmails is a read-only legacy fetch (ADR-0003: reads are
// ungated), so it stays ungated here too — otherwise a read would be
// held as an approval row.
val SCHEDULED_RUN_ALLOWLIST: Set<String> =
    setOf(
        "search_emails",
        "read_email",
        "getImportantEmails",
        "get_page_text",
        "search_memory",
        "list_schedules",
    )

// Terminal run states, stored in schedule_runs.status. RUNNING is the
// transient claim marker so a double-fire loses the race instead of
// double-running; finish() always overwrites it. A RUNNING row older than
// STALE_RUN_TTL_MS means the process died between claim and finish (the
// only path that leaves RUNNING behind): the next delivery reclaims it
// instead of skipping forever.
const val STALE_RUN_TTL_MS = 10 * 60_000L
enum class ScheduleRunStatus {
    RUNNING,
    SUCCESS,
    BLOCKED_WRITE,
    AUTH_ERROR,
    FAILED,
}

// One recorded run: the idempotency key is scheduleId+fireTime, so a
// double-fired alarm never double-runs the loop.
data class ScheduleRun(
    val scheduleId: String,
    val fireTime: Long,
    val status: ScheduleRunStatus,
    val error: String?,
    val result: String?,
    val durationMs: Long,
)

// The plug shape. Tests use InMemoryScheduleRunStore; Room implements
// this on the phone (RoomScheduleStore in ScheduleDb.kt). claim()
// inserts the RUNNING marker and returns its pid, or null when the key
// already exists (the double-fire loses here, before any LLM call).
interface ScheduleRunStore {
    fun claim(scheduleId: String, fireTime: Long): Long?

    fun finish(pid: Long, status: ScheduleRunStatus, error: String?, result: String?, durationMs: Long)

    fun find(scheduleId: String, fireTime: Long): ScheduleRun?

    fun list(): List<ScheduleRun>

    /**
     * Reclaim a stale RUNNING row (process death between claim and finish).
     * Returns the existing pid when the row was RUNNING and older than ttlMs
     * (marks it FAILED so the crash is recorded), else null. Genuinely
     * concurrent duplicates stay DuplicateSkipped.
     */
    fun resetStaleRunning(scheduleId: String, fireTime: Long, nowMs: Long, ttlMs: Long = STALE_RUN_TTL_MS): Long?
}

class InMemoryScheduleRunStore : ScheduleRunStore {
    private val rows = mutableMapOf<Pair<String, Long>, Pair<Long, ScheduleRun>>()
    private var nextPid = 0L

    @Synchronized
    override fun claim(scheduleId: String, fireTime: Long): Long? {
        val key = scheduleId to fireTime
        if (key in rows) return null
        nextPid += 1
        rows[key] = nextPid to ScheduleRun(scheduleId, fireTime, ScheduleRunStatus.RUNNING, null, null, 0L)
        return nextPid
    }

    @Synchronized
    override fun finish(pid: Long, status: ScheduleRunStatus, error: String?, result: String?, durationMs: Long) {
        val entry = rows.entries.firstOrNull { it.value.first == pid } ?: return
        rows[entry.key] = pid to ScheduleRun(entry.key.first, entry.key.second, status, error, result, durationMs)
    }

    @Synchronized
    override fun find(scheduleId: String, fireTime: Long): ScheduleRun? = rows[scheduleId to fireTime]?.second

    @Synchronized
    override fun resetStaleRunning(scheduleId: String, fireTime: Long, nowMs: Long, ttlMs: Long): Long? {
        val entry = rows[scheduleId to fireTime] ?: return null
        val (pid, run) = entry
        if (run.status != ScheduleRunStatus.RUNNING) return null
        if (nowMs - fireTime <= ttlMs) return null
        rows[scheduleId to fireTime] = pid to run.copy(status = ScheduleRunStatus.FAILED, error = "stale RUNNING claim reclaimed")
        return pid
    }

    @Synchronized
    override fun list(): List<ScheduleRun> = rows.values.map { it.second }
}

// Expired/revoked Gmail credentials surface as read failures, never
// silent no-ops: the runner maps them to the Reconnect notification.
fun isGmailAuthFailure(error: String): Boolean {
    val text = error.lowercase()
    // Token/phrasing signals are inherently Gmail-shaped.
    if (text.contains("invalid_grant") ||
        text.contains("token refresh") ||
        text.contains("token expired") ||
        text.contains("reconnect gmail") ||
        text.contains("not connected")
    ) return true
    // Bare codes ("HTTP 401") also come from the LLM transport (bad model
    // key), so they only count inside a Gmail-shaped message
    // ("Gmail: HTTP 401 ..."). Otherwise a bad LLM key would post a
    // misleading "Reconnect Gmail" notification.
    if (!text.contains("gmail")) return false
    return text.contains("401") || text.contains("unauthorized")
}

// The loop's registry view for scheduled runs: allowlisted reads run
// as-is; every other tool keeps its name/schema (so the model recovers
// from the refusal like any structured error) but is forced through
// the Confirmation gate with neutered executors, so even a caller that
// skips the gate cannot reach the real transport or store.
fun scheduledRegistry(full: ToolRegistry): ToolRegistry {
    val out = ToolRegistry()
    for (name in full.names()) {
        val definition = full.find(name) ?: continue
        if (name in SCHEDULED_RUN_ALLOWLIST) {
            out.register(definition)
        } else {
            out.register(
                definition.copy(
                    requiresConfirmation = true,
                    execute = { blockedScheduledTool(name) },
                    approvedExecute = null,
                ),
            )
        }
    }
    return out
}

private fun blockedScheduledTool(name: String): ToolResult =
    ToolResult.Failure(
        "tool '$name' never runs in scheduled runs: recorded for approval instead; nothing ran",
        retryable = false,
    )

// Headless chat over the single-shot fallback seam (ADR-0004): every
// loop turn replays the rendered messages through the candidates in
// Headless chat over the structured fallback seam (ADR-0004): the loop's
// messages ride intact. Same pinning as the foreground chat: the first
// provider that answers handles every later turn, so private results are
// never replayed to a second host mid-run. Tried unions every provider
// that saw the prompt, even on failure, so the outcome can disclose it.
// This class lives in the agent package so the Worker never imports UI.
// The advertised set defaults to allToolSchemas(); threading the run's
// registry view through is follow-up work (kept out of this PR).
class HeadlessChatLlm(
    private val candidates: List<NamedProvider>,
) : ChatLlmProvider {
    val tried: MutableList<String> = mutableListOf()
    var lastProvider: String? = null
        private set
    private var pinned: String? = null

    override fun chat(messages: List<ChatMessage>): LlmResult {
        val effective =
            pinned?.let { name -> candidates.filter { it.name == name } } ?: candidates
        try {
            val fb = planWithFallbackMessages(messages, effective.ifEmpty { candidates })
            for (name in fb.tried) if (name !in tried) tried += name
            if (pinned == null) pinned = fb.providerName
            lastProvider = fb.providerName
            return fb.result
        } catch (e: FallbackExhausted) {
            for (name in e.tried) if (name !in tried) tried += name
            throw e.cause as? Exception ?: RuntimeException(e)
        }
    }
}

// What the summary note says: the loop's answer (or its error), plus
// how many attempted writes are waiting for approval, plus the retry
// disclosure when private context was replayed across providers (ADR-0004).
fun scheduledSummary(
    answer: String?,
    error: String?,
    blockedCount: Int,
    providersTried: List<String> = emptyList(),
    providerUsed: String? = null,
): String {
    val base = answer ?: error.orEmpty()
    val withBlocked = if (blockedCount > 0) "$base (${blockedCount} request(s) need your approval)" else base
    if (providersTried.size <= 1 || providerUsed == null) return withBlocked
    return "$withBlocked (retried on $providerUsed; tried ${providersTried.joinToString(", ")})"
}

fun scheduledRunNotification(
    schedule: Schedule,
    answer: String?,
    error: String?,
    blockedCount: Int,
    authFailed: Boolean,
    providersTried: List<String> = emptyList(),
    providerUsed: String? = null,
): ScheduleNotification =
    if (authFailed) {
        ScheduleNotification(
            "Reconnect Gmail",
            "Routine \"${schedule.instruction}\" couldn't reach Gmail (sign-in expired). Reconnect Gmail to resume routines.",
        )
    } else {
        buildResultNotification(schedule, scheduledSummary(answer, error, blockedCount, providersTried, providerUsed))
    }

sealed interface ScheduledRunOutcome {
    data class Ran(
        val run: ScheduleRun,
        val notification: ScheduleNotification,
        val blocked: List<PendingApproval>,
        val providersTried: List<String> = emptyList(),
        val providerUsed: String? = null,
    ) : ScheduledRunOutcome

    data class DuplicateSkipped(val run: ScheduleRun) : ScheduledRunOutcome
}

// One headless run: claim the idempotency key first (double-fires stop
// here, before any LLM call), run the AgentLoop on the read-only view
// with a headless confirmer (no UI exists to approve: every write is
// rejected and recorded), then store the run and build the note.
// A stale RUNNING row (process death between claim and finish) is reclaimed
// after STALE_RUN_TTL_MS instead of skipping forever; a fresh RUNNING row
// is a genuine duplicate and skips.
// Never throws: a transport failure becomes a FAILED run row.
fun runScheduledRoutine(
    schedule: Schedule,
    fireTime: Long,
    llm: ChatLlmProvider,
    registry: ToolRegistry,
    runs: ScheduleRunStore,
    approvals: PendingApprovalStore,
    nowMs: () -> Long = System::currentTimeMillis,
): ScheduledRunOutcome {
    val startMs = nowMs()
    fun executeWithPid(pid: Long): ScheduledRunOutcome {
        val blockedCalls = mutableListOf<ToolCall>()
    val readOnly = scheduledRegistry(registry)
    val loop =
        AgentLoop(
            llm,
            readOnly,
            confirm = { call ->
                if (call.name in SCHEDULED_RUN_ALLOWLIST) {
                    ApprovalDecision.Approved
                } else {
                    blockedCalls += call
                    ApprovalDecision.Rejected
                }
            },
        )
    val result =
        try {
            loop.run(schedule.instruction)
        } catch (e: Exception) {
            AgentLoopResult.Failed(
                "scheduled run failed: ${e.message ?: e.javaClass.simpleName}",
                emptyList(),
                emptyList(),
            )
        }
    val failureTexts =
        result.steps.mapNotNull { (it.result as? ToolResult.Failure)?.error } +
            ((result as? AgentLoopResult.Failed)?.error?.let { listOf(it) } ?: emptyList())
    val authFailed = failureTexts.any(::isGmailAuthFailure)
    val blocked =
        blockedCalls.map { call ->
            approvals.save(
                PendingApproval(
                    scheduleId = schedule.id,
                    toolName = call.name,
                    arguments = call.arguments,
                    reason = "scheduled run: sensitive tool held for user approval, never executed",
                ),
            )
        }
    val answer = (result as? AgentLoopResult.Answered)?.answer
    val error = (result as? AgentLoopResult.Failed)?.error ?: failureTexts.firstOrNull()
    val status =
        when {
            authFailed -> ScheduleRunStatus.AUTH_ERROR
            blockedCalls.isNotEmpty() -> ScheduleRunStatus.BLOCKED_WRITE
            result is AgentLoopResult.Answered -> ScheduleRunStatus.SUCCESS
            else -> ScheduleRunStatus.FAILED
        }
    val durationMs = (nowMs() - startMs).coerceAtLeast(0L)
    val resultText = answer ?: error.orEmpty()
    runs.finish(pid, status, error, resultText, durationMs)
    val run =
        runs.find(schedule.id, fireTime)
            ?: ScheduleRun(schedule.id, fireTime, status, error, resultText, durationMs)
    val tried = (llm as? HeadlessChatLlm)?.tried?.toList().orEmpty()
    val used = (llm as? HeadlessChatLlm)?.lastProvider
    return ScheduledRunOutcome.Ran(
        run,
        scheduledRunNotification(schedule, answer, error, blockedCalls.size, authFailed, tried, used),
        blocked,
        tried,
        used,
    )
    }

    runs.find(schedule.id, fireTime)?.let { existing ->
        if (existing.status != ScheduleRunStatus.RUNNING) return ScheduledRunOutcome.DuplicateSkipped(existing)
        val reclaimed = runs.resetStaleRunning(schedule.id, fireTime, startMs)
            ?: return ScheduledRunOutcome.DuplicateSkipped(existing)
        return executeWithPid(reclaimed)
    }
    val pid =
        runs.claim(schedule.id, fireTime)
            ?: return ScheduledRunOutcome.DuplicateSkipped(
                runs.find(schedule.id, fireTime)
                    ?: ScheduleRun(schedule.id, fireTime, ScheduleRunStatus.RUNNING, null, null, 0L),
            )
    return executeWithPid(pid)
}
