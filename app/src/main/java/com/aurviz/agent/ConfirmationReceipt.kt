package com.aurviz.agent

// Ticket #70 T5: settled Confirmation receipts (spec #65). Every
// sensitive-tool outcome maps to one explainable receipt, so the
// foreground card and the held list read the same canonical language
// (GLOSSARY Confirmation + PendingApproval — never "Approval"). Pure
// derivation, JVM-tested in ConfirmationReceiptTest. The Compose card and
// the held list render receipt.title/receipt.detail: the held list via
// receiptForHeld, the settled history card via the receipt stamps in
// AgentViewModel (approve/approve-edited/reject + the direct email path);
// the mapping to the existing ConfirmationStatus card states is documented
// on each outcome below.

// The five settled shapes a sensitive call can end in. Maps to the card's
// ConfirmationStatus as: ALLOWED/CARD_APPROVED/EDITED_APPROVED -> Approved,
// DENIED -> Denied, HELD -> Pending (still awaiting the in-app decision).
enum class ConfirmationOutcome {
    ALLOWED,
    CARD_APPROVED,
    EDITED_APPROVED,
    DENIED,
    HELD,
}

// One settled receipt: the outcome plus the human-readable explanation.
// Titles name the decision; details name the act and what ran or didn't.
data class ConfirmationReceipt(
    val outcome: ConfirmationOutcome,
    val title: String,
    val detail: String,
)

/**
 * Ran with no Confirmation card: an ungated read, an allow-always
 * Preference auto-allow (#68), or a bounded scheduled-consent exact match
 * (#67). [why] names the path (e.g. "read-only fetch",
 * "allow-always grant for friend@work.com",
 * "bounded scheduled consent for sched_1").
 */
fun receiptForAllowed(call: ToolCall, why: String): ConfirmationReceipt =
    ConfirmationReceipt(
        ConfirmationOutcome.ALLOWED,
        "Ran without a Confirmation card — ${call.name}",
        "$why. Policy checks still applied. Nothing needed your decision.",
    )

/**
 * Settles one gated execution from the single gated path
 * (ToolRegistry.executeGated). Approved with the model's exact args is a
 * card approval; approved with different effective args is an edited
 * approval. Anything unapproved is denied: reject and timeout arrive as
 * Success(cancelled_by_user), policy/validation refusals as Failure —
 * each names its reason, and nothing ran in any of them.
 */
fun receiptForGated(call: ToolCall, gated: GatedExecution): ConfirmationReceipt {
    if (gated.approved) {
        return if (gated.effectiveArgs == call.arguments) {
            ConfirmationReceipt(
                ConfirmationOutcome.CARD_APPROVED,
                "Confirmed — ran ${call.name}",
                "You confirmed the exact card — ran with the shown details.",
            )
        } else {
            ConfirmationReceipt(
                ConfirmationOutcome.EDITED_APPROVED,
                "Confirmed with edits — ran ${call.name}",
                "You confirmed with edited details — ran with your edits.",
            )
        }
    }
    val failure = gated.result as? ToolResult.Failure
    if (failure != null) {
        return ConfirmationReceipt(
            ConfirmationOutcome.DENIED,
            "Denied — nothing ran (${call.name})",
            "The Confirmation was denied (${failure.error}) — nothing ran.",
        )
    }
    val data = (gated.result as? ToolResult.Success)?.data as? Map<*, *>
    return if (data?.get("reason") == "confirmation_timed_out") {
        ConfirmationReceipt(
            ConfirmationOutcome.DENIED,
            "Denied — nothing ran (${call.name})",
            "The Confirmation timed out — nothing ran.",
        )
    } else {
        ConfirmationReceipt(
            ConfirmationOutcome.DENIED,
            "Denied — nothing ran (${call.name})",
            "You denied this Confirmation — nothing ran.",
        )
    }
}

/**
 * A scheduled run attempted a sensitive call with no one present: the
 * attempt became a PendingApproval row for the in-app held list. Approve
 * runs it exactly once, reject drops it — nothing ran yet.
 */
fun receiptForHeld(held: PendingApproval): ConfirmationReceipt =
    ConfirmationReceipt(
        ConfirmationOutcome.HELD,
        "Held for a later decision — ${held.toolName}",
        "A scheduled run attempted ${held.toolName} with no one present — " +
            "decide in the held list. Nothing ran yet.",
    )
