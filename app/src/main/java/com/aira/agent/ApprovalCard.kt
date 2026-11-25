package com.aira.agent

// Ticket 12/25: the Confirmation card model (ADR-0003, gaps §3C). The
// preview card is the Confirmation gate's UI: it shows the exact
// To/Subject/Body the model asked to send — never a paraphrase — and
// highlights recipients not seen before, so the user approves the real
// send. This file is pure derivation (JVM-tested in ApprovalCardTest);
// the Compose sheet in ui/sheets only renders an ApprovalCard, and the
// ViewModel builds the known-address set from the mailbox senders.

// One recipient line on the card: the exact address plus whether it was
// seen before (a mailbox sender or a previously approved recipient).
data class CardRecipient(
    val address: String,
    val unseen: Boolean,
)

// What the gate UI shows before a send_email runs: the exact fields
// from the Tool call plus the unseen flags. Null for non-send calls —
// reads never raise a card (ADR-0003 gates sends, not reads).
data class ApprovalCard(
    val toolName: String,
    val to: List<CardRecipient>,
    val subject: String,
    val body: String,
) {
    val unseen: List<String>
        get() = to.filter { it.unseen }.map { it.address }
}

/** Recipients of this send that are not in the known set (case-insensitive). */
fun unseenRecipients(to: List<String>, knownAddresses: Set<String>): List<String> {
    val known = knownAddresses.map { it.trim().lowercase() }.toSet()
    return to.filter { it.trim().lowercase() !in known }
}

/** Card for one gated call, or null when the call needs no send card. */
fun approvalCardFor(call: ToolCall, knownAddresses: Set<String>): ApprovalCard? {
    if (call.name != "send_email") return null
    val rawTo = (call.arguments["to"] as? String).orEmpty()
    val addresses = rawTo.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val unseen = unseenRecipients(addresses, knownAddresses).toSet()
    return ApprovalCard(
        toolName = call.name,
        to = addresses.map { CardRecipient(it, unseen = it in unseen) },
        subject = (call.arguments["subject"] as? String).orEmpty(),
        body = (call.arguments["body"] as? String).orEmpty(),
    )
}

/** Known addresses = sender addresses seen in the mailbox. "Boss
 *  <boss@work.com>" contributes the address inside the brackets, not
 *  the display name. */
fun knownAddressesFromInbox(rows: List<EmailSummary>): Set<String> =
    rows.mapNotNull { senderAddress(it.from) }.toSet()

private fun senderAddress(from: String): String? {
    val bracketed = Regex("<([^<>@\\s]+@[^<>@\\s]+)>").find(from)?.groupValues?.get(1)
    if (bracketed != null) return bracketed.lowercase()
    val bare = from.trim().lowercase()
    return bare.takeIf(::isPlausibleEmail)
}
