# Validation log

Every case where the AI was wrong and how it was caught. Add an entry with file:line evidence before moving on.

## 2026-11-23 — ADR/code drift found during grill (against HEAD `3fcfbff`)

- **ADR-0002 said periodic work; code uses one-time work.** `docs/adr/0002-workmanager-alarm-scheduling.md` claimed "WorkManager periodic work", but `enqueueRoutineWork` (`app/src/main/java/com/aurviz/agent/ScheduleWork.kt:40-51`) builds a `OneTimeWorkRequest` with `enqueueUniqueWork` — no periodic builder anywhere in `app/src`. Fixed by ADR-0002 rewrite.
- **ADR-0003 said whole-Plan confirmation; decisions require per-tool-call.** `docs/adr/0003-gmail-oauth-confirmation.md` required "explicit whole-Plan Confirmation before any send", but a plan cannot be approved before later steps exist, and the code had no registry gate at all. Fixed by ADR-0003 amendment.
- **ADR-0001 hybrid vs Room-only shipped path.** `docs/adr/0001-supermemory-room-hybrid.md` described Supermemory behind `MemoryRepository`, but the only wiring is `RoomMemoryStore` (`app/src/main/java/com/aurviz/agent/ui/AgentViewModel.kt:72`); Supermemory appears only in a comment (`app/src/main/java/com/aurviz/agent/Memory.kt:5`) and an unread sample key. Fixed by ADR-0001 amendment.
- **ADR-0004 promised picker UI and DeepSeek/Meta keys; code has neither.** `docs/adr/0004-openai-compatible-llm.md` promised an in-app picker and Meta key wiring, but `ProviderPicker.kt:3-4` notes the screen stays unwired and no `META_*` BuildConfig field exists. Fixed by ADR-0004 amendment (config-only, Groq fallback).
- **Stale comment: "memory-only for now".** `app/src/main/java/com/aurviz/agent/Email.kt:37` says credentials are memory-only, but `TokenVault` (EncryptedSharedPreferences) exists and `AgentViewModel.kt:75` loads tokens from it. Comment predates the vault.
