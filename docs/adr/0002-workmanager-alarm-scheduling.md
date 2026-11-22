# Exact alarm → expedited unique Worker → agent loop for Schedules

Status: rewritten 2026-11-23 — supersedes the periodic-work design (code already used one-time unique work, never periodic).

WorkManager alone is inexact under Doze and can miss 9AM sharp times, while an always-on ForegroundService drains battery. We store Schedules in Room and trigger via `setExactAndAllowWhileIdle` at the next cron time; the receiver enqueues an expedited unique Worker (`REPLACE`, so one routine never doubles); the Worker runs the agent loop; re-arm happens in the receiver and on boot plus `TIMEZONE_CHANGED`/`TIME_SET`. No WorkManager periodic work. Receiver work moves off raw `Thread` + `goAsync` onto the Worker path. Revoked `SCHEDULE_EXACT_ALARM` falls back to inexact plus a persistent Settings prompt.

Scheduled runs use the read-only tool allowlist only (`search_emails`, `read_email`, `get_page_text` via the background OkHttp path, `search_memory`, `list_schedules`). A scheduled `send_email` or schedule write is never executed: it produces a `PendingApproval` Room row plus a notification, and the user approves in-app. Runs are recorded in `schedule_runs` (scheduleId, fireTime, status, error, result, durationMs) with idempotency key scheduleId + fireTime. An expired Gmail token posts a "Reconnect Gmail" notification instead of failing silently.
