package com.aurviz.agent

import java.util.concurrent.LinkedBlockingQueue

// Ticket P0: one-shot Confirmation gate binding.
//
// The Confirmation card approves exactly one Tool call in exactly one Task
// Execution (GLOSSARY Confirmation, ADR-0003). The gate binds each open
// card to (runId, callId) so:
// - approval of card A can never release a different call in run B,
// - a late double-tap cannot queue a decision for the next call,
// - stale callbacks (old card id) are ignored.
//
// One gate per process: the UI shows one card at a time and the ViewModel
// runs one Task Execution at a time (single-flight). Each gated call opens
// a fresh single-slot queue; resolve() only offers when the queue is empty
// (one-shot) and the ids match.
class ApprovalGate {
    companion object {
        // Bounded Confirmation wait: the card never blocks the loop forever.
        // Matches the loop total budget; the confirmer maps expiry to
        // TimedOut (cancelled_by_user), never an auto-approve.
        const val APPROVAL_TIMEOUT_MS = 300_000L
    }
    private val lock = Any()
    private var nextRunId = 0L
    private var activeRunId: Long? = null
    private var gateRunId: Long? = null
    private var gateCallId: String? = null
    private var gateQueue: LinkedBlockingQueue<ApprovalDecision>? = null

    /** Start a Task Execution. Returns null when one is already running. */
    fun startRun(): Long? =
        synchronized(lock) {
            if (activeRunId != null) return null
            nextRunId += 1
            activeRunId = nextRunId
            nextRunId
        }

    fun finishRun(runId: Long) =
        synchronized(lock) {
            if (activeRunId == runId) activeRunId = null
            if (gateRunId == runId) {
                gateRunId = null
                gateCallId = null
                gateQueue = null
            }
        }

    fun isRunning(): Boolean = synchronized(lock) { activeRunId != null }

    /**
     * Open the card for one gated call. Returns the fresh one-shot queue
     * the confirmer blocks on, or null when this run is no longer active.
     */
    fun openGate(runId: Long, callId: String): LinkedBlockingQueue<ApprovalDecision>? =
        synchronized(lock) {
            if (activeRunId != runId) return null
            val queue = LinkedBlockingQueue<ApprovalDecision>(1)
            gateRunId = runId
            gateCallId = callId
            gateQueue = queue
            queue
        }

    /**
     * Resolve the open card. Only the matching (runId, callId) releases it,
     * and only once — a second tap while the queue holds a decision is
     * ignored so it cannot leak into the next call.
     */
    fun resolve(runId: Long, callId: String, decision: ApprovalDecision): Boolean =
        synchronized(lock) {
            val queue = gateQueue ?: return false
            if (gateRunId != runId) return false
            if (gateCallId != callId) return false
            if (queue.isNotEmpty()) return false
            queue.offer(decision)
        }

    /** Test/current-card introspection (no blocking). */
    fun currentCallId(): String? = synchronized(lock) { gateCallId }
}
