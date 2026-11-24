# AURVIZ Evolution Plan — Multi-Agent Architecture, UI/UX & Feature Audit

**Date:** 2026-11-23 · **Mode:** read-only discovery + planning (no source edits made)
**Inputs:** full Kotlin/Compose codebase, 17 rendered Framebin reference pages in `/tmp/aurviz_audit/`,
`GLOSSARY.md`, `ARCHITECTURE.md`, `TEST_PLAN.md`, `gaps.md`, `HANDOFF.md`, `docs/adr/0001–0005`
**Note on visual assets:** the 8 Aurviz app screenshots (`1000166689.jpg`–`1000166696.jpg`) were **not
present on disk** (searched `~/Downloads`, repo root, `/tmp`). Sub-Agent 2 therefore performed a rigorous
static UI audit from source instead of screenshot-to-code comparison. All 4 Framebin PDFs were rendered to
PNG and visually inspected page-by-page. Re-run the screenshot comparison before Phase 1 if the images resurface.

Glossary compliance: this document uses **Agent, Plan, Tool, Memory, Preference, Interaction, Schedule,
Confirmation, Voice Command, Task Execution** exactly. Banned terms (chatbot, assistant, workflow, routine,
cron job, consent popup, dictation, job run) appear only when quoting code identifiers or describing violations.

---

## 1. Executive Summary & Current State Baseline

### Architecture health: strong core, thin durability layer

The backend is a genuinely agentic system, not a chat wrapper, and the seams are clean:

- **Agent loop** (`agent/AgentLoop.kt:123-203`): hand-rolled loop, LLM → `ToolRegistry.executeGated` → gated
  suspend on the `ApprovalGate` nonce → append `role:"tool"` JSON matched by `tool_call_id`. Max 8 steps,
  per-step 90 s / total 5 min. 13 Tools, 3 gated (`send_email`, `create_schedule`, `cancel_schedule`).
- **Confirmation** is per-sensitive-Tool-call via the `ApprovalGate` one-shot nonce (`ApprovalGate.kt:20-102`),
  additionally wrapped by `DefaultSendPolicy` inside `executeSendEmail` (`Email.kt:345-399`). Correct depth.
- **Scheduling** is exact-alarm → expedited unique Worker → read-only headless loop (5-Tool allowlist) with
  `PendingApproval` deferral (`ScheduleAlarm.kt`, `ScheduleWork.kt`, `ScheduledRun.kt:385-473`). Receivers do
  no DB work on raw threads. Boot/timezone re-arm covered.
- **Memory** is Room-only (`AppDb` v5, `memories`+FTS, `schedules`, `schedule_runs`, `pending_approvals`),
  keyword + importance/recency ranking, no cloud provider. Matches ADR-0001.
- **LLM fallback** is ordered candidates + skip-blank-key + 429-retry honoring `retry-after`, 401 correctly
  never falls back, with "retried on Y" disclosure (`ProviderPicker.kt`, `AgentViewModel.kt:158-192`).
- **Test suite:** ~337 passing unit tests across ~30 classes — the safety net every phase below leans on.

The honest gaps, all small-footprint and all fixable without new heavy dependencies:

1. `Interaction` is a GLOSSARY entity but exists only as an in-memory list capped at 50 (`AgentUiState.kt:37-52`,
   `AgentViewModel.kt:288-331`) — lost on process death. No audit trail survives reboot.
2. `onRmsChanged` is dead-ended (`MainActivity.kt:232`) and `onPartialResults` reaches state but only as a
   transcript string — the voice orb never reacts to loudness, interim text is indistinguishable from final.
3. `effectiveProvider()` (`AgentViewModel.kt:259-260`) can pin a keyless provider first on every run instead of
   auto-selecting the first configured (`Set`) provider — first-run failure mode with a confusing error.
4. `rememberFact` dedups on exact match only (`Memory.kt:90-100`) and **nothing in the shipped path ever calls
   `markUsed` on recall** — the recency engine (`lastUsed`/`useCount`, Room v2→3 columns) is starved.
5. `PerfTimings` (`AgentUiState.kt:21-25`) holds only last-run singles; `schedule_runs` stores only terminal
   `(status, error, result, durationMs)` — no Last-vs-Avg, no success rate, no per-provider attribution.
6. UI layer has accumulated copy and chrome defects (raw `send_email` / `create_schedule` labels, raw cron
   strings on Schedule cards, `New chat`/`thread` vs Session, `Settings` vs Preference, stray back chevrons on
   root tabs, dual mic/waveform buttons, oversized key forms, battery prose wall) — all fixable in Compose
   `foundation` with zero backend risk.

### Visual maturity: solid foundation system, inconsistent application

`VelaTheme` (Midnight/Daylight/System) is fully specified with both palettes, and the `foundation`-only
component kit (Composer, PlanCard, ConfirmationCard, SideDrawer, VoiceOrb, OptionGroup/Toggle/Row) is real.
But token bypass (hardcoded `24.dp`/`20.dp` radii, `200.dp` card widths, `640.dp` page caps), duplicated provider
surfaces, and glossary-drunk copy keep it from looking top-1%. No redesign or Material migration is needed —
consistent application of the existing system plus Framebin reference patterns gets there.

### Test suite status: green, must stay green

Every phase below ends with `./gradlew testDebugUnitTest` passing and names the affected test classes plus why
they stay green (additive/defaulted changes only; new tests added alongside, never rewrites of existing assertions).

---

## 2. Screen-by-Screen & Sheet-by-Sheet Visual Critique & Redesign Spec

**Global build rules (apply everywhere):** pills/inputs 44–56 dp heights; list rows 48–56 dp; CTAs 48 dp;
cards `Theme.shapes.medium` (20 dp), pills `fullyRounded`, inputs 12–16 dp, sheets top 28 dp; always
`1dp Theme.colors.edge` border, focused `1.5dp primary`, zero `shadow` (layer via
`background → surface → surfaceVariant` + hairline); titles `title`, rows `label`, helpers `caption`,
content `body`, PIN/code `code`; screen sides 16 dp, card inner 16 dp, rows 16×12 dp; every tappable gets
`clickable` + ripple, disabled alpha 0.4; content-descriptions on all icon-only buttons. Single `primary`
accent reserved for send/confirm/active/progress only (Signal rule).

### 2.1 Command screen (`ui/screens/CommandScreen.kt`)

| # | Current flaw (file:line) | Target reference pattern | Exact `foundation` change |
|---|---|---|---|
| C1 | Duplicate provider selectors: header `ModelPill` (`:128`) + Composer internal `ModelPill` (`components/Composer.kt:105`) + bottom-sheet raw ids `null/deepseek/gemini/meta` (`CommandScreen.kt:230-247`) | ChatGPT §1.1 single-row pill composer — one model affordance, human names | Keep **one** `ModelPill` in the composer row only; delete header pill. Sheet lists human names (`Gemini`, `DeepSeek`, `Meta`) + `Set` badge, never raw ids or `null`. |
| C2 | Ambiguous dual mic/waveform: mic `IconButton` always visible (`Composer.kt:108-119`) **plus** `Waveform` Voice Command button when empty (`:170-181`); two handlers (`onMicTap` vs `onVoiceClick`) | Todoist Ramble §4.1 — one voice entry | One voice button per state: empty composer shows **either** mic **or** waveform, not both. `onMicTap` opens `VoiceCommandSheet` directly (same destination as `onVoiceClick`); remove the fork. Disabled dimming when STT unavailable. |
| C3 | Empty-state `SuggestionCard`s in a `Column`, fixed `width(200.dp)`, `maxLines=1` truncates (`:142-169`, `SuggestionCard.kt:30`) | ChatGPT §1.2 starter cards — 48–52 dp rows, full-width | Full-width rows (`fillMaxWidth`, 48–52 dp, `medium` radius, `surface` + hairline), leading 16 dp glyph + `label` + trailing chevron; no fixed width, `maxLines=2`. |
| C4 | Recalled-Memory chips tiny/low-contrast (`:264-281`, hardcoded 12 sp + 50 dp radius) | ChatGPT §1.5 disclosure rows | Chips 36 dp height, `fullyRounded`, `surfaceVariant` fill, `caption` 12/16 in `onSurface` (not muted-on-variant); tapping a chip opens the Memory detail. |
| C5 | `StepRow` error duplication `"${title} (${error})"` (`:522-530`); two `Retry failed Tool` buttons co-render (`:125-127,187-189,491-493`) | Todoist §4.3 activity feed — one status line per row | Title shows step outcome once; error in `caption` below, never appended to title. Single retry affordance in `ActiveRunSection` only. Expandable Plan timeline (P0 U1): per-step Tool card with args summary + latency ms + retry on retryable failure. |
| C6 | Email + web cards duplicate container code (`:302-336,338-370`) | Proton §2.1 elevation layers | Extract one `FeedCard` (card + 0.5 dp edge + 12 dp) used by both; snippet/from/subject `maxLines=2/1/1`. |
| C7 | Glossary copy: `New chat`, `thread N` (`:118-126`) | — | `New session`, `Session · N turns` (matches `startNewSession`, `Session.kt`). |
| C8 | `pendingCall.name` raw in `ConfirmationCard` header → `Confirmation · send_email` (`:426,443,474`, `ConfirmationCard.kt:126`) | Proton §2.2 human review fields | Human Tool labels map (`send_email`→`Send email`, `create_schedule`→`Create schedule`); header `Confirmation · Send email`. |

### 2.2 Browser screen (`ui/screens/BrowserScreen.kt`, `ui/WebViewHolder.kt`)

| # | Current flaw | Target pattern | Exact change |
|---|---|---|---|
| B1 | No URL/title bar: `url` local state (`:74`) is write-only, never synced from `state.webPage`; stale typed text after navigation | Todoist §4.4 browser chrome — 52 dp top bar, X / URL pill / share / more | Top bar 52 dp + bottom hairline: 32 dp Close, center URL pill (`surface` + hairline + `fullyRounded`, 36 dp, `caption` muted truncated + 12 dp lock icon, synced from `state.webPage`), 32 dp share + more. 2 dp `primary` progress line under bar. Bottom bar 56 dp: back/share/tabs/menu 48 dp muted buttons. |
| B2 | Default white `WebView` washes status-bar icons in Midnight (`WebViewHolder.kt:40-57`, no background set) | Proton §2.1 dark-slate layers | `setBackgroundColor` to Midnight `background` when dark theme; add `WebSettingsCompat` dark branch; `domStorageEnabled=false`, `loadsImagesAutomatically=false` (text-extraction usage). |
| B3 | `webError` bare `Text(destructive)` (`:139-145`), retry only via `Go` | Signal §3.2 status banners | 56 dp banner (`small` radius, `surfaceVariant` + hairline), 20 dp muted icon + `caption` + text-button `Retry` in `primary`. |
| B4 | Inspector: hardcoded 20 dp top radius, `Links (N)` truncates, every link a full-width Secondary `Button` (`:158-261`) | ChatGPT §1.4 grouped cards | Inspector `large` (28 dp) top radius; `ChoiceTile` labels short (`Page/Text/Links`); link rows 52 dp outline rows (not filled buttons) with `maxLines=1` + chevron. |
| B5 | `Save to Memory` exists but no source attribution | Strategy U5 | Saved Memory chip shows source URL chip; one-tap `Save to Memory` stamps URL (P0). |

### 2.3 Schedules screen (`ui/screens/SchedulesScreen.kt`)

| # | Current flaw | Target pattern | Exact change |
|---|---|---|---|
| S1 | Raw cron string on every card (`:153-157`, `formatScheduleCron` → `0 9 * * 1,3,5`) | Todoist §4.2 Upcoming timeline — human cadence headers | Show only `formatScheduleTime` (`Daily at 09:00`, `Mon, Wed, Fri at 09:00`). Cron string removed from UI (keep in logs/tests). |
| S2 | Crowded footer: `OptionToggle` + custom `Run Now` pill (bypasses `Button`) + morphing `Trash→Confirm?` (`:158-222`) | Signal §3.1 restrained accent; ChatGPT §1.4 52 dp rows | Footer: toggle (weight 1 f) + 44 dp outline `Run now` `Button` + `Trash` `IconButton`; delete arms an inline `Confirmation` row (`Delete Schedule? Delete/Keep`), never a morphing pill. Custom pills → real `Button` (ripple/disabled semantics). |
| S3 | Preview `ConfirmationCard(toolLabel="create_schedule", title="Save Schedule", approve="Save Schedule")` — Save/Save duplication (`:104-114`); draft cleared before preview succeeds (`:97-98`) | Proton §2.2 review fields | `toolLabel="Create schedule"`, title `Review Schedule`, approve `Save Schedule`; clear draft only on successful save. |
| S4 | Approvals fallback title leaks raw ids (`:248`); held-email path duplicates `approvalCardFor` (`:229-267`) | Proton §2.2 | Route all held items through `approvalCardFor()` (single source); fallback title `Schedule request` + human Tool label, never `sched_…` ids. |
| S5 | Recent Runs show raw `scheduleId` + `durationMs ms` jargon; debug `statusLine` dump at bottom (`:268-326`) | Todoist §4.3 reporting feed | Run rows 64 dp: 32 dp status dot + title `Task Execution completed · <summary>` + `caption` time + status chip (tinted bg `copy(alpha=0.14f)`); durations as `1.2 s`; remove `statusLine` dump. |
| S6 | Missing glanceable reliability | Strategy U4 (P0) | Each card: `Fires in 13 h 22 m · next 09:00` countdown line (`caption`); run-history status dots. |

### 2.4 Memory screen (`ui/screens/MemoryScreen.kt`)

| # | Current flaw | Target pattern | Exact change |
|---|---|---|---|
| M1 | Facet pills echo raw `category/source` mixed case (`you told me`, `Web note`) (`:75-79,124-152`); unselected contrast weak | ChatGPT §1.6 disclosure rows / Todoist §4.3 filter chips | Human-cased pill labels (`Told me`, `Preference`, `Web note`); selected `primary/onPrimary`, unselected hairline + `onSurface`; 36 dp height, 8 dp gaps. Persist selected facet (Strategy U7). |
| M2 | Meta line `"${category} · ${source}[ · Used N×]"` in `code` style (`:180-185`); icon-only edit/delete 20 dp, no labels | Signal §3.3 list rhythm | Meta in `caption` muted (not `code`); edit/delete as labeled text-buttons or 48 dp-hit icon buttons with content-descriptions. |
| M3 | `+ Add Memory` full-width Primary + two full `ModalBottomSheet`s for single-field forms (`:226-289`) | Signal §3.4 confirmation sheets | Single-field forms become inline expanding rows or a compact sheet (max 320 dp content); keep sheets for delete Confirmation only. |
| M4 | Undo toast slides from wrong edge, wrong icon (`Toast.kt:39-44`, `MemoryScreen.kt:96-117`) | Signal §3.2 banners | Bottom-anchored slide; `Trash` muted icon (not `CheckCircle primary`) for a delete; 4 s timeout kept. |
| M5 | Empty-vs-no-match conflated (`:154-159`); no match highlight | Strategy U7 | Separate `No memories yet` vs `No matches for "q"` states; bold match spans in results. |

### 2.5 Settings screen (`ui/screens/SettingsScreen.kt`)

| # | Current flaw | Target pattern | Exact change |
|---|---|---|---|
| T1 | 4× expanded API-key forms always visible; fields clear on dispose; `Set xor value` badge oddity (`:69-84,181-249`) | ChatGPT §1.4 grouped 52 dp cards; Proton §2.4 status cards | Collapsed provider rows (52 dp, human name + `Set`/`Not set` badge + chevron); tap expands **one** form (masked existing value `••••1234`, Paste/Save/Clear). Collapses scroll from ~4 forms to 4 rows. |
| T2 | `send_email` raw title (`:257`); mixed verb casing (`Auto-run` vs `Enforced`); `Settings` title vs Preference | Glossary pass (P0, zero-risk) | Titles: `Send email`, `Read emails`, `Forget Memory`, `Scheduled writes`; screen/tab renamed `Preferences`; consistent `Enforced / Automatic / Ask every time` casing. |
| T3 | Battery 4-sentence footer wall pushes telemetry below fold (`:277-284`) | Signal §3.2 banners | Footer → one `caption` line + `Learn more` link opening a detail row; telemetry moves above Permissions. |
| T4 | Telemetry: cramped `MetricColumn`s, count+provider conflated (`:331-368`) | ChatGPT §1.5 usage bars | Three labeled bars (Plan/Tool/Recall: label row + 4 dp `primary` fill + Last/Avg values) + summary `DeepSeek · 12 Interactions · 3 Confirmations` (P0 U6; Avgs land in Phase 2). |
| T5 | Repeated `Icons.Bell` for alarms + background (`:291,308`); alarm/timer wording vs Schedule | Glossary pass | Distinct icons (`Bell` vs `BatteryCharging`/`Clock`); rows read `On-time schedules` / `Background schedules`. |
| T6 | Two stacked primaries in paste-fallback (`:146-180`) | Signal §3.1 one primary per viewport | `Use pasted link` Primary + `Dismiss` outline Secondary (not two primaries). |

### 2.6 Sheets (3)

| Sheet | Flaw | Target | Change |
|---|---|---|---|
| `VoiceCommandSheet.kt` | No RMS wiring (comment admits); `Use as command` enabled on blank transcript; `Tap to speak/Listening…` Primary+Secondary pair | Todoist Ramble §4.1 (hero) | Full spec §4.1: quoted partial transcript (`title`, max 4 lines) + `Listening…` caption; `Canvas` 48 dp waveform (24–32 bars, 3 dp wide, 4 dp gap, rounded caps, `primary` recent / `border` trailing, ring buffer 32 from `onRmsChanged` @ ~100 ms throttle); 56 dp Pause / Confirm-check circles + `Cancel` text-button; interim muted vs final `onSurface`; Confirm disabled until transcript non-blank; states listening/paused/confirm-ready/error+Retry. |
| `EmailConfirmationSheet.kt` | Generic `Confirmation` title; duplicates inline `ConfirmationCard` idiom; dismiss = reject may surprise | Proton §2.2 hairline-divided review | Title `Send this email?`; single field-stack container (`background` + hairline on `surface` sheet) with full-bleed 1 dp dividers, label-`caption` + value-`body` rows, Edit toggles editable fields; actions `Confirm send` 48 dp Primary + `Edit`/`Reject` outline; scrim-tap = cancel (maps to reject path explicitly). |
| `ForgetMemorySheet.kt` | Correct scoping; long memories overflow (no `maxLines`) | Signal §3.4 destructive sheet | Truncate preview rows (`maxLines=3` + `+N more`); red 48 dp `Delete N` + outline `Keep` stacked vertically (never side-by-side primary+destructive); explicit tap required. |

### 2.7 Bottom navigation / top-bar chrome (`AgentNav.kt`, `AppScaffold.kt`, `ScreenScaffold.kt`, `AppBar.kt`, `Page.kt`)

- **Stray back chevrons on root tabs:** `Page(title, onBack)` always renders `CaretLeft` (`Page.kt:37-46`), and
  all root destinations pass `onBack=goCommand` (`SchedulesScreen.kt:85`, `MemoryScreen.kt:92-95`,
  `SettingsScreen.kt:91`). **Fix:** `onBack=null` on the 5 root tabs (chevron only on pushed/detail pages).
- **Double status-bar insets:** `ScreenScaffold` + `CommandScreen` header + `AppBar` each pad status bars.
  **Fix:** pad once in `ScreenScaffold`; headers use fixed 56 dp without extra insets.
- **Theme-mode wiring invisible from UI dir:** `AppScaffold.kt:13-18` uses default `VelaTheme{}` while
  `AgentViewModel.kt:227` persists `ThemeMode`. **Fix (verify in Phase 1):** Activity must wrap content in
  `VelaTheme(colors = themeColors(dark = mode == DARK || (mode == SYSTEM && isSystemInDarkTheme())))`;
  add a unit/instrumentation assertion that each mode resolves the correct palette.
- **Pending-approval dot:** keep the 7 dp `warning` dot (`AgentNav.kt:266-273`) but add content-description
  (`N approvals waiting`) and use `primary` instead of `warning` per restrained-accent rule.
- **Daylight/Midnight tokens:** palette itself is sound (both modes fully specified; no `Color.White` leaks;
  `Icons.kt:50` black fill safe via `Icon{}` tint). Risks to fix: `onBackgroundMuted` at 12–13 sp is marginal
  (bump secondary copy to 13 sp minimum or darken light-mode muted one step); 8 % `edge` hairlines nearly
  invisible (raise to ~12 %); settled `ConfirmationCard alpha 0.72f` likely fails 4.5:1 in Daylight — use muted
  text colors instead of alpha dimming.

---

## 3. Backend & Data Architecture Evolution Plan

All changes are additive, Room-pattern-mirrored, zero new heavy dependencies (`room`, `org.json` already present).

### 3.1 Room evolution: `Interaction` persistence (v5 → v6)

**Schema** (mirror `MIGRATION_3_4` DDL style in `ScheduleDb.kt:60-87`; new entity in `ScheduleDb.kt` or new
`InteractionDb.kt`, registered on `AppDb` `MemoryDb.kt:65-75`, version → 6):

```kotlin
@Entity(tableName = "interactions")
data class InteractionRow(
  @PrimaryKey val pid: String,            // UUID
  val command: String,
  val responseText: String,
  val providerUsed: String?,              // nullable: forward-compatible
  val retryNotice: String?,
  val confirmTitle: String?, val confirmDetail: String?, val confirmStatus: String?,
  val planMs: Long?, val toolMs: Long?, val recallMs: Long?,   // §3.4 columns
  val providersTried: String?,            // JSON array string via org.json
  val stepsJson: String,                  // TimelineStep list serialized (org.json)
  val recalledJson: String,               // recalled Memory ids (org.json)
  val failed: Boolean, val timestampMs: Long,
)
```

Plus nullable `planMs/toolMs/recallMs/providerUsed/providersTried` columns on `schedule_runs` (same migration).
DAO: `insert / listRecent(limit) ORDER BY timestampMs DESC / deleteOlderThan(cutoff) / clearAll`.
`MIGRATION_5_6`: two `CREATE TABLE` + five `ALTER TABLE schedule_runs ADD COLUMN` statements; register at both
`addMigrations` call sites (`AgentViewModel.kt:199-201`, `ScheduleWork.kt:60-62,164-166`).

**Hook:** `AgentViewModel.recordCompleted()` (`:294-331`) — after the in-memory append, `viewModelScope.launch(IO)
{ dao.insert(...) }` fire-and-forget; feed hydrates from Room on launch (cap 50 UI / 200 stored, trim beyond).

**Test safety:** existing `ChatTimelineTest`, `ViewModelLoopWiringTest`, `SessionContextTest` assert in-memory
list shape — unchanged. Add `InteractionMigrationTest` (5→6 data intact) mirroring
`MemoryTest.migration23AddsNewColumnsWithDefaults`.

### 3.2 Voice RMS + partial transcript pipeline

- `MainActivity.kt:232` `onRmsChanged(rmsdB)`: throttle (`now - lastRms > 120ms`) → new
  `AgentViewModel.onVoiceLevel(db)` setter → `VoiceUiState.levelDb: Float = -2f` (`AgentUiState.kt:27-32`,
  defaulted so all constructors stay green). `VoiceOrb` reads `levelDb` for scale/pulse when
  `orb == Listening/Hearing` (`VoiceCommandSheet.kt:44`).
- `MainActivity.kt:193-198` `onPartialResults` already calls `onVoicePartial`; extend `VoiceUiState` with
  `isPartial: Boolean = true`, cleared by `onVoiceFinal` (`AgentViewModel.kt:1399-1415`). Sheet renders interim
  muted + `Listening…` vs final full style.
- **Deps:** none (framework callback already implemented). **Tests:** `VoiceInputTest` (11 pure-function tests)
  untouched; add 2 tests on partial-flag transitions.

### 3.3 Provider auto-selection & error recovery

- New pure helper `firstConfiguredProvider(preferred, settings): String` in `ProviderPicker.kt:177+` (next to
  `resolveProvider()`): if preferred's effective key is blank, return first of
  `[deepseek, meta, gemini]`-order with non-blank effective key, else existing default.
- Call from `AgentViewModel.effectiveProvider()` (`:259-260`) and the headless path (`ScheduleWork.kt:92`,
  currently raw `BuildConfig.LLM_DEFAULT_PROVIDER` — same bug for scheduled runs).
- 401 → no-fallback (`LlmProvider.kt:239-249`) deliberately preserved; candidate *order* logic untouched.
- **Tests:** `ProviderPickerTest` order tests (`:62-86`) + `LlmKeysTest` stay green (only the preferred *input*
  is pre-corrected). Add 3 tests: selected-blank-falls-to-Set, all-blank-keeps-default, headless default.

### 3.4 Telemetry & Task Execution observability

- Extend `PerfTimings` with defaulted fields (`AgentUiState.kt:21`):
  `planAvgMs, toolAvgMs, recallAvgMs: Double? = null`, `runs: Int = 0`, `successRate: Double? = null`,
  `perProviderMs: Map<String, Double> = emptyMap()`. Pure updater `recordRun(perf, planMs, toolMs, recallMs,
  success, providerUsed)` next to `approvalWaitMs` (`AgentViewModel.kt:136-156`) — cumulative moving average.
- Replace the 5 direct `perf.copy(planMs=…)` writes (`:452-464, :474-486, :594, :850, :1328/:1346`) with
  `recordRun(...)`, threading `chat.lastProvider` + Answered/Failed bit at loop sites. Last-value semantics
  preserved → `PerfTimingsTest`, `ViewModelLoopWiringTest` green.
- Schedule runs: `ScheduleRunDao.finish` (`ScheduleDb.kt:116-117`) gains the timing/provider fields;
  `runScheduledRoutine` (`ScheduledRun.kt:392-458`) threads already-available `durationMs`,
  `HeadlessChatLlm.tried/lastProvider` (`:449-450`) through. Foreground runs persist via §3.1 table.
- **Follow-up (Phase 3):** per-Tool latency map (`tool-name → last-ms` around `ToolRegistry.executeGated`,
  Strategy B6) surfaced in the expandable Plan cards.

### 3.5 Memory deduplication & recall analytics

- `Memory.kt:90-100` `rememberFact()`: compare on `text.trim().lowercase()` (+ `source.trim()`), preserving
  original casing. New `MemoryDao.findDuplicate` (`MemoryDb.kt:35`):
  `SELECT * FROM memories WHERE lower(trim(text)) = lower(trim(:text)) AND source = :source LIMIT 1`.
  (Optional v6 `normalizedText` column + backfill for O(1) lookup — default to Kotlin-side normalization to
  avoid a second migration unless profiling demands it.)
- **Recall accounting (the actual bug):** after `recallFor(...).take(max)` in the `search_memory` executor
  (`ToolRegistry.kt:506-523`), call `store.markUsed(hit.id)` per returned hit (bounded, try/catch-guarded so a
  bookkeeping failure never breaks the read — mirrors `Memory.kt:155`). Optionally also at
  `AgentViewModel.kt:1307`. This feeds the existing `lastUsed`/`useCount` ranking (`Memory.kt:120-124`).
- **Tests:** `MemoryTest` dedup/recall/markUsed/FTS/migration tests stay green (wider match is a superset);
  add `rememberCaseVariantsKeepOne` + `searchMemoryRecordsUseCount`. `ToolRegistryTest` result shapes unchanged.

### 3.6 Other lightweight backend improvements (all additive, zero test breakage)

1. **Step metadata:** `ExecutedStep` (`AgentLoop.kt:75-81`) gains `stepIndex, latencyMs, providerName: String? =
   null` (defaulted) → powers U1 cards; `toTimeline()`/`buildTimeline` unchanged → `AgentLoopTest` green.
2. **Structured error taxonomy:** `ToolResult.Failure` (`ToolRegistry.kt:29-33`) gains `code: String = "error"`
   (`auth/network/validation/timeout/blocked/…`) mapped at existing failure sites; `error` strings preserved →
   retry-vs-abort copy + success-rate metric.
3. **Incremental step streaming:** `AgentEvent.StepProgress(call, chunkIndex)` (`AgentLoop.kt:53-73`);
   `LoopUiProgress.withEvent` ignores unknown events by default → live multi-call progress, timeline tests green.
4. **Dedup surfacing:** `save_memory` executor (`ToolRegistry.kt:479-492`) adds `"deduped": true` when the store
   size didn't grow — no signature change.
5. **WebView/text polish:** extract 25 s latch to `const TIMEOUT_SEC` (`WebViewBridge.kt:18-99`); strip
   `<script>/<style>` pre-split in `parsePage` (`Web.kt:94-104`, verify fixtures first); secure-posture
   one-liners in `WebViewHolder.obtain` (`domStorageEnabled=false`, `loadsImagesAutomatically=false`).

---

## 4. Prioritized Feature & Polish Matrix (P0 / P1 / P2 / Out-of-Scope)

`Type`: UI-Only vs Backend+UI. `Test impact`: affected classes + verdict.

| Priority | Item | Type | Exact files touched | Rubric impact | Test impact |
|---|---|---|---|---|---|
| **P0** | Glossary copy sweep (human Tool labels, drop cron strings, `New session`, `Preferences`, schedule wording, chip casing) | UI-Only | `CommandScreen.kt`, `SchedulesScreen.kt` (`:106,153-157,248`), `SettingsScreen.kt` (`:91,257,279-303`), `MemoryScreen.kt` (facets), `ConfirmationCard.kt:126`, `AgentViewModel.kt:1434-1437` (keep fn, drop call) | Code quality, schedules, confirmation | None — copy only; add 1 assertion test that no raw `*_schedule`/`send_email` id reaches UI helpers |
| **P0** | Expandable Plan timeline: per-step Tool card + args summary + latency + retry affordance (U1) | UI-Only (+ reads existing events) | `CommandScreen.kt:499-533` (StepRow), `components/PlanCard.kt`, `AgentUiState.kt` (TimelineStep) | Agentic loop, tools, Task Execution, telemetry | `AgentLoopTest`, `ChatTimelineTest` green (render-only) |
| **P0** | Single voice entry + Voice sheet state fixes (disable empty confirm, error+Retry) (U2-core) | UI-Only | `components/Composer.kt:97-183`, `sheets/VoiceCommandSheet.kt`, `AgentNav.kt:114` | Voice, confirmation | `VoiceInputTest` green |
| **P0** | Schedule countdown + run status dots/feed rows (U4) | UI-Only | `SchedulesScreen.kt:134-318`, `AgentViewModel.kt` (`nextTriggerMillis` diff) | Schedules, telemetry, Task Execution | `ScheduleTest`, `ScheduledRunTest` green (format-only) |
| **P0** | Settings collapse: provider rows + telemetry bars + battery footer→link (U6, T1, T3, T4) | UI-Only | `SettingsScreen.kt:69-84,146-368` | Telemetry, code quality | None (layout only) |
| **P0** | Root-tab chevron removal + status-bar inset single-pad + approval-dot description | UI-Only | `Page.kt:37-46`, `Schedules/Memory/SettingsScreen` `onBack` args, `ScreenScaffold.kt:24`, `CommandScreen.kt:109-113`, `AgentNav.kt:266-273` | Code quality | None |
| **P0** | Memory undo restores full row (B2) | Backend+UI | `AgentViewModel.kt:525-530` (`restoreMemory`), `MemoryScreen.kt:85-90` | Memory, code quality | Add 1 test: delete→undo preserves category/importance/useCount |
| **P0** | Browser URL bar sync + error banner (B1, B3) | UI-Only | `BrowserScreen.kt:74-156`, `WebViewHolder.kt:40-57` | Browser automation | `BrowserPolicyTest`, `WebTest` green |
| **P1** | `Interaction` Room persistence + Task Execution history feed (B1, §3.1) | Backend+UI | `ScheduleDb.kt` (entity/DAO), `MemoryDb.kt:65-75` (v6), `MIGRATION_5_6`, `AgentViewModel.kt:199-201,294-331`, `ScheduleWork.kt:60-62,164-166`, Command history drawer | Interaction, plan, confirmation, Task Execution | Add `InteractionMigrationTest`; `ChatTimeline/SessionContext/ViewModelLoopWiring` green |
| **P1** | RMS waveform + partial-transcript states (Todoist Ramble full §4.1) | Backend+UI | `MainActivity.kt:193-198,232`, `AgentViewModel.kt:1388-1419`, `AgentUiState.kt:27`, `VoiceCommandSheet.kt`, `components/VoiceOrb.kt:68-102` | Voice Command | `VoiceInputTest` green + 2 new tests |
| **P1** | Provider auto-select first-Set (c) + headless path | Backend+UI | `ProviderPicker.kt:177+` (new helper), `AgentViewModel.kt:259-260`, `ScheduleWork.kt:92` | Code quality, reliability | `ProviderPickerTest`, `LlmKeysTest` green + 3 new tests |
| **P1** | Memory dedup + recall accounting (§3.5) | Backend+UI | `Memory.kt:90-100`, `MemoryDb.kt:35`, `ToolRegistry.kt:479-523`, `AgentViewModel.kt:1307,448-467` | Memory, preference | `MemoryTest`, `ToolRegistryTest` green + 2 new tests |
| **P1** | Rolling PerfTimings (Last vs Avg, success rate, per-provider) + run-history columns (§3.4) | Backend+UI | `AgentUiState.kt:21`, `AgentViewModel.kt:136-156` + 5 call sites, `ScheduleDb.kt:95-117`, `ScheduledRun.kt:392-458`, Settings bars | Telemetry | `PerfTimingsTest`, `ScheduledRunTest` green (Last semantics kept) |
| **P1** | Confirmation receipt actions: Copy / Remember Preference (U3) | UI-Only | `CommandScreen.kt:288-295`, composer `save_memory` path | Confirmation, memory, preference, Interaction | None (existing paths) |
| **P1** | Morning-briefing Schedule preset, 2-tap (B3) | UI-Only | `SchedulesScreen.kt:86-103`, preset constants | Schedules, confirmation, Task Execution | `ScheduleTest` green (parser untouched) |
| **P1** | Browser Cite/Save-to-Memory with URL chip (U5) | UI-Only | `BrowserScreen.kt:215-235`, `save_memory` path | Browser, memory, Interaction | `WebToolsTest` green |
| **P2** | Memory match highlight + facet persistence (U7, M5) | UI-Only | `MemoryScreen.kt:80-159` | Memory, Interaction | `MemoryTest` green |
| **P2** | Schedule run → originating Interaction deep link (B5) | Backend+UI | `AgentNav.kt` (nav args), `SchedulesScreen.kt:268-318` | Schedules, Interaction, Task Execution | Add 1 nav-args test |
| **P2** | Preference quick-action `save_memory(category=preference)` (B4) | UI-Only | Command feed actions, `Memory.kt` ranking test | Preference, memory, agentic loop | Add 1 ranking test |
| **P2** | Step metadata + error taxonomy + StepProgress streaming (F1–F3) | Backend+UI | `AgentLoop.kt:53-81`, `ToolRegistry.kt:29-33,219-231,570-579` | Telemetry, tools | `AgentLoopTest`, `ToolRegistryTest` green (all defaulted) |
| **P2** | Per-Tool latency map in Plan cards (B6) | Backend+UI | `ToolRegistry.executeGated`, `AgentUiState.kt`, `PlanCard.kt` | Telemetry, tools | Add 1 timing test |
| **Out** | Cloud sync / multi-device Memory backup | — | — | — | Violates ADR-0001 Room-only + `allowBackup=false`; auth/conflict/PII surface, zero rubric gain |
| **Out** | Multi-model streaming / parallel-fanout inference | — | — | — | Graded design is ordered-fallback + disclosure (ADR-0004); threading + flaky-test risk for glitter |
| **Out** | Full browser engine / JS automation DSL | — | — | — | Controlled Tool surface + allowlist is the safety story; APK bloat + red-team surface |
| **Out** | New OAuth scopes beyond Gmail PKCE | — | — | — | Multiplies `SECURITY.md` burden; least-privilege seam already audited |
| **Out** | Analytics / crash SDKs | — | — | — | Zero-heavy-deps constraint; PII must never leave device; offline telemetry suffices |
| **Out** | Material3 migration / full visual redesign | — | — | — | Invalidates all screens, minSdk 26 risk, zero rubric needles |
| **Out** | TTS replies / background audio / widgets / Wear OS | — | — | — | New permissions + battery/device-matrix burden (`SCHEDULING_TEST.md` can't absorb) |
| **Out** | Vector-embedding recall (local or cloud) | — | — | — | Explicitly cut (#32, gaps §2.5); keyword + importance/recency + FTS is the defended story |

---

## 5. Phased Execution Roadmap (Spec-Ready Phases)

Each phase is independently shippable, ends with `./gradlew testDebugUnitTest` green, and has a manual checklist.
Sizes: Phase 1 = S, Phase 2 = M, Phase 3 = M, Phase 4 = S–M.

### Phase 1 — Glossary + Chrome + Settings/Command hygiene (UI-only, zero backend risk)

**Scope:** P0 copy sweep (human Tool labels, kill cron strings, `New session`, `Preferences`, schedule wording,
chip casing); root-tab chevron removal; single status-bar pad; approval-dot description; Composer single voice
entry + Voice sheet empty/error states; Schedule countdown + run feed rows; Settings provider-row collapse +
telemetry bars + battery footer→link; Browser URL sync + error banner; Memory undo full-row restore (single
`restoreMemory` fix + test); verify `themeMode`→`VelaTheme` wiring in Activity.
**Tests:** existing suite must pass unmodified + 2 new (undo-restore fidelity; no-raw-Tool-id-in-UI).
`./gradlew testDebugUnitTest` green.
**Manual checklist:** toggle Midnight/Daylight/System × 5 screens; empty composer shows one voice button;
Schedule card shows human cadence + countdown; Settings fits providers above fold; WebView dark page keeps
status icons legible; delete→undo restores category/importance/count.

### Phase 2 — Durability: Interaction history + Memory correctness + provider auto-select

**Scope:** §3.1 `interactions` table + `schedule_runs` timing columns (`MIGRATION_5_6`, both `addMigrations`
sites, `recordCompleted` fire-and-forget write, launch hydrate, Command history drawer read path); §3.5 dedup
normalization + `findDuplicate` + recall `markUsed` hooks; §3.3 `firstConfiguredProvider` + both call sites;
§3.4 `PerfTimings` rolling fields + `recordRun` + 5 call sites + Settings Last-vs-Avg bars.
**Tests:** + `InteractionMigrationTest`, `rememberCaseVariantsKeepOne`, `searchMemoryRecordsUseCount`, 3
provider tests, rolling-average tests. Pin: `MemoryTest`, `ScheduledRunTest`, `ProviderPickerTest`,
`LlmKeysTest`, `PerfTimingsTest`, `ChatTimelineTest`, `SessionContextTest` green.
`./gradlew testDebugUnitTest` green.
**Manual checklist:** reboot → history + receipts persist; `Priya` vs `priya ` saves once; keyless-selected
provider auto-uses the Set key; Settings shows Avg + success rate after 3+ runs; scheduled run rows carry
provider + timings.

### Phase 3 — Voice + Confirmation depth (hero patterns)

**Scope:** §3.2 RMS throttle → `levelDb` → `VoiceOrb` pulse + `isPartial` interim/final styles; full Ramble sheet
(waveform `Canvas`, Pause/Confirm/Cancel, error+Retry); Email sheet Proton restyle (field-stack, Edit toggle,
explicit scrim-cancel); Forget sheet truncation + stacked destructive; receipt Copy / Remember-Preference
actions; morning-briefing preset; Browser Cite/Save-with-URL-chip; expandable Plan cards with latency + retry.
**Tests:** + 2 voice-state tests; `VoiceInputTest`, `AgentLoopTest`, `ToolRegistryTest`, `ApprovalGateTest`,
`ConfirmationGateTest`, `PendingApprovalTest` green. `./gradlew testDebugUnitTest` green.
**Manual checklist:** speak → waveform tracks loudness, interim grey → final white, pause freezes bars;
email sheet readable in both themes; preset creates a Schedule in 2 taps through the unchanged gate; Plan card
expands to per-step ms + retry on a forced failure (airplane-mode run).

### Phase 4 — Polish + proof (telemetry story, deep links, hardening)

**Scope:** per-Tool latency map (B6) in Plan cards; run→Interaction deep link (B5); Preference quick-action
(B4); Memory highlight + facet persistence (U7/M5); step metadata + error codes + `StepProgress` (F1–F3);
WebView `<script>/<style>` strip + latch const + secure flags (F5, fixtures verified); theme-contrast pass
(muted-text step, edge-alpha step, kill `alpha 0.72f` dimming); update `docs/PERF_TIMINGS.md`,
`docs/SCHEDULING_TEST.md` device-pass notes, `docs/VALIDATION_LOG.md` entries per fix.
**Tests:** + nav-args, ranking, timing tests; full suite green. `./gradlew testDebugUnitTest` green.
**Manual checklist:** end-to-end demo script passes uncut — Voice Command → Plan unfolds step-by-step → one
Confirmation → receipt persists → recalled next run → 9 AM Schedule holds its send for in-app approval;
screenshot Midnight + Daylight for the submission.

**Demo script (the 5-minute top-1% proof):** speak a Voice Command → watch the Plan unfold step-by-step →
approve one Confirmation → see the receipt persist as an Interaction → find it recalled tomorrow by a Schedule
that held its send for in-app approval.

### Phase ordering rationale

Phase 1 first because it is pure UI with zero migration risk and instantly lifts reviewer-visible craft.
Phase 2 second because durability (`Interaction` table) is the load-bearing schema change every later feature
reads from — landing it early lets Phases 3–4 build on real history. Phase 3 third because voice/sheet heroes
are the demo centerpiece but depend on Phase 2 state fields. Phase 4 last because observability polish and
docs are only meaningful once the system they describe is final.

---

## 6. Open Decisions & Trade-offs for Human Review

1. **Interaction table home:** new `InteractionDb.kt` file vs extending `ScheduleDb.kt`? Sub-Agent 1 proposes
   `ScheduleDb.kt` (fewer files, mirrors `MIGRATION_3_4`); counter-case is a dedicated file keeping schedule
   concerns pure. Default: extend `ScheduleDb.kt` unless you prefer the split.
2. **`Settings` → `Preferences` rename scope:** glossary says Preference avoids Setting/option, but the bottom-tab
   label `Settings` is platform-conventional and the assignment rubric may literally say "settings". Options:
   (a) rename tab+screen to `Preferences` (glossary-pure), (b) keep `Settings` chrome, use Preference inside copy
   only. Default: (b) — convention beats purity for a tab label; needs your call before Phase 1 copy sweep.
3. **Memory normalization without a column:** Kotlin-side `trim().lowercase()` (zero migration) vs v6
   `normalizedText` column + backfill (O(1) lookup at scale). Current Memory volumes favor Kotlin-side; the
   column can ride `MIGRATION_5_6` nearly free while we're already migrating. Your call.
4. **Voice sheet height:** Todoist Ramble is near-full-screen; Aurviz `ModalBottomSheet` is half-sheet.
   Full-height gives the waveform room but covers the feed the user may want to see. Default: expanded
   half-sheet (560 dp max) with the §4.1 layout compressed — confirm before Phase 3.
5. **`WebView` image loading:** `loadsImagesAutomatically=false` speeds text extraction and saves data but
   degrades visual browsing fidelity. Options: off everywhere, off only for background loads, or a Preference
   toggle. Default: off for background extraction, on for foreground — needs your pick.
6. **Scope lock:** matrix proposes P0→P2 across 4 phases. If submission timeline compresses, the minimal
   top-1% cut is **Phase 1 + Phase 2 only** (craft + durability + correctness) — Phase 3/4 defer cleanly.
   Confirm whether all four phases are in scope or time-box to 1–2.

---

*Plan synthesized 2026-11-23 from 4 parallel sub-agent reports (backend audit, UI static audit, Framebin
pattern catalog, product strategy). No source files were modified. Reference renders kept at `/tmp/aurviz_audit/`.*
