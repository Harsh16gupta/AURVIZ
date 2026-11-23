package com.aurviz.agent

import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

// Ticket P0: one-shot Confirmation gate binding.
//
// The Confirmation card approves exactly one Tool call in exactly one Task
// Execution (GLOSSARY Confirmation, ADR-0003). Each open card gets an
// opaque per-proposal nonce: the card buttons echo the nonce back and
// resolve() only releases the gate when the nonce matches. The Tool call
// id is NOT a capability — AgentLoop.kt regenerates fallback ids
// (call_s0_0, …) on every run, so a stale tap replaying a call id must
// never release a new card. The run id is captured at card-open, never
// re-read at tap time.
// One gate per process: the UI shows one card at a time and the ViewModel
// runs one Task Execution at a time (single-flight). Each gated call opens
// a fresh single-slot queue; resolve() only offers when the queue is empty
// (one-shot) and the nonce matches.
class ApprovalGate {
    companion object {
        // Bounded Confirmation wait: the card never blocks the loop forever.
        // Matches the loop total budget; the confirmer maps expiry to
        // TimedOut (cancelled_by_user), never an auto-approve.
        const val APPROVAL_TIMEOUT_MS = 300_000L
    }

    /** Opaque handle for one open Confirmation card. */
    data class GateTicket(
        val proposalId: String,
        val queue: LinkedBlockingQueue<ApprovalDecision>,
    )

    private val lock = Any()
    private var nextRunId = 0L
    private var activeRunId: Long? = null
    private var gateRunId: Long? = null
    private var gateCallId: String? = null
    private var gateProposalId: String? = null
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
                gateProposalId = null
                gateQueue = null
            }
        }

    fun isRunning(): Boolean = synchronized(lock) { activeRunId != null }

    /**
     * Open the card for one gated call. Returns a fresh one-shot ticket —
     * an opaque per-proposal nonce plus the queue the confirmer blocks on —
     * or null when this run is no longer active. The nonce is unguessable
     * (UUID); the call id is recorded for introspection only and never
     * accepted back as proof.
     */
    fun openGate(runId: Long, callId: String): GateTicket? =
        synchronized(lock) {
            if (activeRunId != runId) return null
            val queue = LinkedBlockingQueue<ApprovalDecision>(1)
            val proposalId = UUID.randomUUID().toString()
            gateRunId = runId
            gateCallId = callId
            gateProposalId = proposalId
            gateQueue = queue
            GateTicket(proposalId, queue)
        }

    /**
     * Resolve the open card. Only the matching opaque proposal nonce
     * releases it, and only once — a second tap while the queue holds a
     * decision is ignored so it cannot leak into the next call. A guessed
     * call id or a stale nonce from an earlier card always fails.
     */
    fun resolve(proposalId: String, decision: ApprovalDecision): Boolean =
        synchronized(lock) {
            val queue = gateQueue ?: return false
            if (gateProposalId != proposalId) return false
            if (queue.isNotEmpty()) return false
            queue.offer(decision)
        }

    /** Test/current-card introspection (no blocking). */
    fun currentCallId(): String? = synchronized(lock) { gateCallId }

    /** Test/current-card introspection (no blocking). */
    fun currentProposalId(): String? = synchronized(lock) { gateProposalId }
}
