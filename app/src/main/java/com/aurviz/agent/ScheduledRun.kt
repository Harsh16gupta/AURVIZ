package com.aurviz.agent

import org.json.JSONArray
import org.json.JSONObject

// Ticket 17/24: scheduled-run deferral seam (ADR-0002, gaps §3E). A
// scheduled run has no user present, so its Confirmation gate never confirms:
// the loop runs on the read-only allowlist (GLOSSARY Schedule — the
// scheduled registry never holds the send tool) and any sensitive attempt
// the model still makes (send_email, schedule writes) becomes a
// PendingApproval row for in-app approval instead of executing. The
// transport is untouched on every path. The Worker persists these rows to
// Room and posts the notification on the device; the in-memory store
// below is the JVM-testable seam.
//
// Ticket 15/28: the row carries a stable id (Room pid / in-memory
// counter) so the in-app screen can confirm or reject one row. take() is
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
// means a previous confirm/reject decision already claimed the row.
sealed interface PendingApprovalOutcome {
    data class Executed(val approval: PendingApproval, val result: ToolResult) : PendingApprovalOutcome
    data object Rejected : PendingApprovalOutcome
    data object AlreadyHandled : PendingApprovalOutcome
}

// In-app confirm: claim the row first so a concurrent double-confirm
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
// Ticket #67 carves the single bounded exception: a send_email whose
// arguments exactly match a creation-time ScheduledSendConsent for the
// owning schedule (plus SendPolicy) runs headless; everything else holds.
// This contradicts the letter of ADR-0002/0003 ("never executes"); the ADR
// amendment lands in #70, flagged here per AGENTS.md instead of silently
// overriding.
val SCHEDULED_SENSITIVE_TOOLS = setOf("send_email", "create_schedule", "cancel_schedule")

// --- Ticket #67: bounded creation-time consent for scheduled sends. ---
// Granted when the user confirms a Schedule whose routine sends exactly one
// templated email (exact recipient set + subject/body template + owning
// Schedule id). Fire-time re-validates the exact match plus the
// deterministic SendPolicy with no human present: exact match runs, any
// drift (recipient, bulk expansion, subject/body, policy deny) defers to
// PendingApproval. Revocable via revoke(); scoped by scheduleId so consent
// never leaks across routines.
data class ScheduledSendConsent(
    val scheduleId: String,
    val recipients: Set<String>,
    val subject: String,
    val body: String,
)

interface ScheduledConsentStore {
    fun save(consent: ScheduledSendConsent): ScheduledSendConsent
    fun find(scheduleId: String): ScheduledSendConsent?
    fun revoke(scheduleId: String)
    fun list(): List<ScheduledSendConsent>
}

class InMemoryScheduledConsentStore : ScheduledConsentStore {
    private val items = mutableMapOf<String, ScheduledSendConsent>()

    @Synchronized
    override fun save(consent: ScheduledSendConsent): ScheduledSendConsent {
        items[consent.scheduleId] = consent
        return consent
    }

    @Synchronized
    override fun find(scheduleId: String): ScheduledSendConsent? = items[scheduleId]

    @Synchronized
    override fun revoke(scheduleId: String) {
        items.remove(scheduleId)
    }

    @Synchronized
    override fun list(): List<ScheduledSendConsent> = items.values.toList()
}

/** Normalize one address for comparison: trimmed + lowercase. */
fun normalizeConsentRecipient(address: String): String = address.trim().lowercase()

/** The recipient set of a send_email call: "to" split on commas. */
fun consentCallRecipients(args: Map<String, Any?>): Set<String> =
    (args["to"] as? String).orEmpty().split(",").map { normalizeConsentRecipient(it) }.filter { it.isNotEmpty() }.toSet()

/** Exact-match check: same recipient set, byte-identical subject and body. */
fun consentMatches(consent: ScheduledSendConsent, args: Map<String, Any?>): Boolean {
    if (consentCallRecipients(args) != consent.recipients) return false
    if ((args["subject"] as? String) != consent.subject) return false
    if ((args["body"] as? String) != consent.body) return false
    return true
}

private fun consentDraftOf(args: Map<String, Any?>): EmailDraft? {
    val to = (args["to"] as? String).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val subject = args["subject"] as? String ?: return null
    val body = args["body"] as? String ?: return null
    if (to.isEmpty()) return null
    return EmailDraft(to = to, subject = subject, body = body)
}

private fun consentPolicyAllows(args: Map<String, Any?>, policy: SendPolicy): Boolean {
    val draft = consentDraftOf(args) ?: return false
    if (draft.to.any(::headerValueHasNewline) || headerValueHasNewline(draft.subject)) return false
    return policy.check(draft) is SendPolicyVerdict.Allow
}

/**
 * Creation-time grant: persists only plausible, non-blank consent.
 * Returns the saved consent, or null when refused (implausible address,
 * blank subject/body — nothing persisted).
 */
fun grantScheduledSendConsent(
    store: ScheduledConsentStore,
    scheduleId: String,
    recipients: Set<String>,
    subject: String,
    body: String,
): ScheduledSendConsent? {
    if (scheduleId.isBlank()) return null
    val normalized = recipients.map { normalizeConsentRecipient(it) }.filter { it.isNotEmpty() }.toSet()
    if (normalized.isEmpty()) return null
    if (normalized.any { !isPlausibleEmail(it) }) return null
    if (subject.isBlank() || body.isBlank()) return null
    val consent = ScheduledSendConsent(scheduleId, normalized, subject, body)
    store.save(consent)
    return consent
}

fun isConsentedSend(
    call: ToolCall,
    scheduleId: String,
    consents: ScheduledConsentStore,
    policy: SendPolicy = DefaultSendPolicy(),
): Boolean {
    if (call.name != "send_email") return false
    val consent = consents.find(scheduleId) ?: return false
    if (!consentMatches(consent, call.arguments)) return false
    return consentPolicyAllows(call.arguments, policy)
}

// One unattended run: the loop with a never-confirming Confirmation hook,
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
                    reason = "scheduled run: sensitive tool held for an in-app Confirmation decision, never executed",
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
// PendingApproval row and the user confirms in-app.
// getImportantEmails is a read-only legacy fetch (ADR-0003: reads are
// ungated), so it stays ungated here too — otherwise a read would be
// held as a Confirmation row.
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
     * Re-asserts the RUNNING lease and returns the existing pid when the
     * row was RUNNING and older than ttlMs, else null. The row is never
     * terminally marked here, so a second crash keeps the due run
     * retryable; finish() alone records the outcome. Genuinely concurrent
     * duplicates stay DuplicateSkipped.
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
        // Synchronized = one winner; the lease is re-asserted, never
        // terminally marked, so a second crash keeps the due run retryable.
        rows[scheduleId to fireTime] = pid to run.copy(status = ScheduleRunStatus.RUNNING, error = "stale RUNNING claim reclaimed; retrying")
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
// Ticket #67: when scheduleId + consents are supplied, send_email carries
// a consent-aware approvedExecute — the fire-time judge approves only the
// exact match, and the executor re-checks consent + policy defensively so
// a forged Approved without consent still cannot reach the transport.
fun scheduledRegistry(
    full: ToolRegistry,
    scheduleId: String? = null,
    consents: ScheduledConsentStore? = null,
    sendPolicy: SendPolicy = DefaultSendPolicy(),
): ToolRegistry {
    val out = ToolRegistry()
    for (name in full.names()) {
        val definition = full.find(name) ?: continue
        if (name in SCHEDULED_RUN_ALLOWLIST) {
            out.register(definition)
        } else if (name == "send_email" && scheduleId != null && consents != null) {
            val realApproved = definition.approvedExecute
            out.register(
                definition.copy(
                    requiresConfirmation = true,
                    execute = { blockedScheduledTool(name) },
                    approvedExecute = { args ->
                        val consent = consents.find(scheduleId)
                        if (consent != null && consentMatches(consent, args) && consentPolicyAllows(args, sendPolicy)) {
                            realApproved?.invoke(args) ?: blockedScheduledTool(name)
                        } else {
                            blockedScheduledTool(name)
                        }
                    },
                ),
            )
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
        "tool '$name' never runs in scheduled runs: recorded for a Confirmation decision instead; nothing ran",
        retryable = false,
    )

// Headless chat over the structured fallback seam (ADR-0004): the loop's
// messages ride intact. Same pinning as the foreground chat: the first
// provider that answers handles every later turn, so private results are
// never replayed to a second host mid-run. Tried unions every provider
// that saw the prompt, even on failure, so the outcome can disclose it.
// This class lives in the agent package so the Worker never imports UI.
// The advertised set is the scheduled run's read-only registry view: the
// model sees allowed reads plus gated writes by name, and attempts on the
// latter become PendingApproval rows instead of silent refusals.
class HeadlessChatLlm(
    private val candidates: List<NamedProvider>,
    private val tools: JSONArray = allToolSchemas(),
) : ChatLlmProvider {
    val tried: MutableList<String> = mutableListOf()
    var lastProvider: String? = null
        private set
    private var pinned: String? = null

    override fun chat(messages: List<ChatMessage>): LlmResult {
        val effective =
            pinned?.let { name -> candidates.filter { it.name == name } } ?: candidates
        try {
            val fb = planWithFallbackMessages(messages, effective.ifEmpty { candidates }, tools)
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
// how many attempted writes are waiting for a Confirmation decision, plus the retry
// disclosure when private context was replayed across providers (ADR-0004).
// Total failure still discloses who saw the prompt — the line names the
// tried hosts instead of going silent when no provider answered.
fun scheduledSummary(
    answer: String?,
    error: String?,
    blockedCount: Int,
    providersTried: List<String> = emptyList(),
    providerUsed: String? = null,
): String {
    val base = answer ?: error.orEmpty()
    val withBlocked = if (blockedCount > 0) "$base (${blockedCount} request(s) need your Confirmation)" else base
    if (providersTried.size <= 1) return withBlocked
    val retryBit =
        if (providerUsed != null) " (retried on $providerUsed; tried ${providersTried.joinToString(", ")})"
        else " (tried ${providersTried.joinToString(", ")}; no provider answered)"
    return "$withBlocked$retryBit"
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
// with a headless confirmer (no UI exists to confirm: every write is
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
    consents: ScheduledConsentStore = InMemoryScheduledConsentStore(),
    sendPolicy: SendPolicy = DefaultSendPolicy(),
    nowMs: () -> Long = System::currentTimeMillis,
): ScheduledRunOutcome {
    val startMs = nowMs()
    fun executeWithPid(pid: Long): ScheduledRunOutcome {
        val blockedCalls = mutableListOf<ToolCall>()
    val readOnly = scheduledRegistry(registry, schedule.id, consents, sendPolicy)
    val loop =
        AgentLoop(
            llm,
            readOnly,
            confirm = { call ->
                if (call.name in SCHEDULED_RUN_ALLOWLIST) {
                    ApprovalDecision.Approved
                } else if (call.name == "send_email" && isConsentedSend(call, schedule.id, consents, sendPolicy)) {
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
                    reason = "scheduled run: sensitive tool held for an in-app Confirmation decision, never executed",
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
