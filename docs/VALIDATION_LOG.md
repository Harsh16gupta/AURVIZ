# Validation log

Every case where the AI was wrong and how it was caught. Add an entry with file:line evidence before moving on.

## 2026-11-23 — ADR/code drift found during grill (against HEAD `3fcfbff`)

- **ADR-0002 said periodic work; code uses one-time work.** `docs/adr/0002-workmanager-alarm-scheduling.md` claimed "WorkManager periodic work", but `enqueueRoutineWork` (`app/src/main/java/com/aurviz/agent/ScheduleWork.kt:40-51`) builds a `OneTimeWorkRequest` with `enqueueUniqueWork` — no periodic builder anywhere in `app/src`. Fixed by ADR-0002 rewrite.
- **ADR-0003 said whole-Plan confirmation; decisions require per-tool-call.** `docs/adr/0003-gmail-oauth-confirmation.md` required "explicit whole-Plan Confirmation before any send", but a plan cannot be approved before later steps exist, and the code had no registry gate at all. Fixed by ADR-0003 amendment.
- **ADR-0001 hybrid vs Room-only shipped path.** `docs/adr/0001-supermemory-room-hybrid.md` described Supermemory behind `MemoryRepository`, but the only wiring is `RoomMemoryStore` (`app/src/main/java/com/aurviz/agent/ui/AgentViewModel.kt:72`); Supermemory appears only in a comment (`app/src/main/java/com/aurviz/agent/Memory.kt:5`) and an unread sample key. Fixed by ADR-0001 amendment.
- **ADR-0004 promised picker UI and DeepSeek/Meta keys; code has neither.** `docs/adr/0004-openai-compatible-llm.md` promised an in-app picker and Meta key wiring, but `ProviderPicker.kt:3-4` notes the screen stays unwired and no `META_*` BuildConfig field exists. Fixed by ADR-0004 amendment (config-only, Groq fallback).
- **Stale comment: "memory-only for now".** `app/src/main/java/com/aurviz/agent/Email.kt:37` says credentials are memory-only, but `TokenVault` (EncryptedSharedPreferences) exists and `AgentViewModel.kt:75` loads tokens from it. Comment predates the vault.
- **ADR-0004 said dropped providers are unwired, but DeepSeek still is.**
  `docs/adr/0004-openai-compatible-llm.md` declares Groq the single fallback
  with DeepSeek "unwired", but `ProviderPicker.kt:112-113,141-146` still reads
  `BuildConfig.DEEPSEEK_API_KEY/_BASE_URL` and `LlmProvider.kt:234-238` still
  serves a `"deepseek"` branch, and `app/build.gradle.kts` still emits the
  debug fields. Caught during ticket-37 doc pass (`grep DEEPSEEK app/src`).
  Not removed in ticket 37 (prod behavior freeze); recorded for the
  follow-up to either rewire the ADR or delete the branch.

## 2026-11-23 — ticket 37 submission proof (against `f251cf2`)

- **No device attached, so no device results are claimed.** `adb devices`
  lists nothing in this environment; `docs/SCHEDULING_TEST.md` records the
  green unit subset and the exact human-pass method instead of fabricating
  the 2-minute/kill/screen-off/force-idle/reboot run. Profiler screenshot
  likewise recorded as method-only in `docs/PERF_TIMINGS.md`.
- **Timing medians are JVM-measured, not device SLOs.** `PerfTimingsTest`
  reports 10-run medians (recall 1.348 ms, prompt 0.027 ms, registry
  0.878 ms over 500 synthetic rows) with the raw runs in stdout; the
  top-k trim comparison (~50× render, ~100× prompt size) is the before/after
  optimization. STT / first-token / end-to-end stay human-pass items.
- **Sample had dead keys and missed OAuth IDs.** `local.properties.sample`
  carried `SUPERMEMORY_API_KEY` / `META_LLAMA_API_KEY` (referenced nowhere
  in `app/src` or `app/build.gradle.kts`) and lacked the
  `GOOGLE_OAUTH_CLIENT_ID/SECRET` the build reads. Cleaned to match the
  ADRs; DeepSeek lines kept and marked legacy because the debug
  `BuildConfig` + provider branches still read them.
