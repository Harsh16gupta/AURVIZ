package com.aurviz.agent

// Ticket #68 (spec #65 T3): revocable per-recipient allow-always
// Preference (GLOSSARY Preference). A send to an allowed recipient skips
// the Confirmation card; everything else still asks. The store only
// records the grant — the deterministic SendPolicy still runs inside
// executeGated after any Confirmation decision, so policy denial always wins over
// allow-always, and bulk/evil-forward never leak through. The eligibility
// rule requires exactly one plausible recipient: multi-address sends are
// never eligible, and there is no blanket allow-all (empty and wildcard
// addresses are not plausible and never match).

/** Revocable per-recipient allow-always grants. Addresses are stored
 *  normalized (trimmed, lowercased); implementations must apply the
 *  grant immediately — revocation takes effect on the next decision. */
interface AllowAlwaysStore {
    fun isAllowed(address: String): Boolean

    fun allow(address: String)

    fun revoke(address: String)

    fun list(): Set<String>
}

class InMemoryAllowAlwaysStore : AllowAlwaysStore {
    private val lock = Any()
    private val allowed = mutableSetOf<String>()

    override fun isAllowed(address: String): Boolean =
        synchronized(lock) {
            val normalized = normalizeAllowAlways(address)
            normalized.isNotEmpty() && normalized in allowed
        }

    override fun allow(address: String) {
        synchronized(lock) {
            val normalized = normalizeAllowAlways(address)
            // Only plausible addresses are grantable: blank and wildcard
            // grants are refused at the store, and the eligibility rule
            // re-checks plausibility, so no caller can honor a blanket.
            if (isPlausibleEmail(normalized)) allowed += normalized
        }
    }

    override fun revoke(address: String) {
        synchronized(lock) { allowed -= normalizeAllowAlways(address) }
    }

    override fun list(): Set<String> = synchronized(lock) { allowed.toSet() }
}

/** Canonical grant key: surrounding whitespace is irrelevant and grants
 *  match case-insensitively. Blank normalizes to empty (never stored). */
fun normalizeAllowAlways(address: String): String = address.trim().lowercase()

/** Recipients of one send_email call, split and cleaned for the
 *  eligibility check. The Confirmation card derivation (approvalCardFor) renders the
 *  same split for the unseen flag. */
fun allowAlwaysRecipients(args: Map<String, Any?>): List<String> =
    (args["to"] as? String).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** Whether this call may skip the Confirmation card: a send_email to
 *  exactly one plausible, explicitly allowed recipient. Anything else —
 *  reads, schedule writes, multi-recipient or unlisted sends — still asks.
 *  Eligibility never approves anything by itself; the caller still runs
 *  the single gated path, so SendPolicy keeps the final word. */
fun allowAlwaysEligible(call: ToolCall, store: AllowAlwaysStore): Boolean {
    if (call.name != "send_email") return false
    val recipients = allowAlwaysRecipients(call.arguments)
    if (recipients.size != 1) return false
    val sole = recipients.single()
    if (!isPlausibleEmail(sole)) return false
    return store.isAllowed(sole)
}
