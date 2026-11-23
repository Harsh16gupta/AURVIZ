# Gmail API OAuth2 with send+read and per-tool-call confirmation gate

Status: amended 2026-11-23 — per-tool-call confirmation replaces whole-Plan confirmation.

SMTP app-passwords are simpler for a demo but weaker for security review and lack scoped revocation. The 9AM "check important emails and prepare a summary" needs READ, not just send. We use Gmail API OAuth2 (PKCE, Settings connect) with least-privilege `gmail.send` + `gmail.readonly`, tokens in EncryptedSharedPreferences.

A Plan cannot be approved up front because later steps depend on earlier results and the draft does not exist yet, so confirmation is per sensitive tool call, not per Plan: `send_email`, `create_schedule`, and `cancel_schedule` set `requiresConfirmation`; reads, searches, and lists do not. The gate lives in the tool registry, so the model has no path around it — the wrapper suspends the loop until the UI returns approve, edit, or reject, and reject returns `{status:"cancelled_by_user"}` to the model. The existing preview cards become the gate's UI: show exact To/Subject/Body, highlight recipients not previously seen. A deterministic policy check runs before `send_email` regardless of what the model says, and the injection red-team test (an evil-forward email must produce a confirmation card, never a send) is the acceptance criterion. Scheduled runs never hold the send tool (see ADR-0002).

## Amendment 2026-11-24 — bounded creation-time consent exception (spec #65 T2/T5)

The sentence above is superseded for one narrow case. When the user
confirms a Schedule whose routine sends exactly one templated email, the
creation-time confirmation grants bounded consent: the exact recipient
set plus the subject/body template, scoped to the owning Schedule id
(`ScheduledSendConsent`, revocable). At fire time the judge re-validates
with no human present — exact match plus the deterministic SendPolicy
screen runs headless; any drift (different recipient, bulk expansion,
changed subject/body, policy deny) defers to `PendingApproval`, and
schedule writes always defer. Expired credentials still post the
"Reconnect Gmail" prompt, never a silent send. Language is canonical
per GLOSSARY: Confirmation for the decision plus human act,
PendingApproval for the deferred unattended decision; the standalone
"Approval" term is retired (code-internal `ApprovalDecision` /
`ApprovalGate` / `NeedsApproval` identifiers remain as the gate
mechanics and are not user language).
