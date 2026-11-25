# Aira Personal Agent - Production-Readiness, Security & Product Audit

**Date:** 2026-11-24  
**Role:** Staff Android & AI Systems Architect  
**Scope:** Full read-only source audit across `app/build.gradle.kts`, `AndroidManifest.xml`, `app/src/main/java/com/aira/agent/**`, `app/src/test/java/com/aira/agent/**` (49 test classes / 526 unit tests), and all repository documentation (`README.md`, `ARCHITECTURE.md`, `SECURITY.md`, `GLOSSARY.md`, `TEST_PLAN.md`, `gaps.md`, `HANDOFF.md`, `docs/**`).

---

## Executive Summary & Architecture Verdict

Aira has a **remarkably clean, well-bounded core architecture** that outperforms typical assignment submissions:
- **Production-Grade Core Seams:** The hand-rolled `AgentLoop` (`AgentLoop.kt:102-204`), schema-validating `ToolRegistry` kernel (`ToolRegistry.kt:129-234`), opaque UUID-nonce `ApprovalGate` (`ApprovalGate.kt:20-102`), deterministic `DefaultSendPolicy` (`Email.kt:298-316`), and provider-pinned fallback (`PinnedFallbackChatLlm` in `ChatFallback.kt:16-40`) are cleanly separated, pure-JVM testable, and faithful to `AGENTS.md` and `GLOSSARY.md`.
- **Production-Grade Background Scheduler:** The exact-alarm → `AlarmReceiver` → expedited unique `ScheduleWorker` (`REPLACE`) → read-only `scheduledRegistry` pipeline (`ScheduleAlarm.kt`, `ScheduleWork.kt`, `ScheduledRun.kt`) with `(scheduleId, fireTime)` Room idempotency and stale-`RUNNING` lease reclamation (`ScheduleDb.kt:130-139`) is hardened to staff-level Android standards.
- **Fragile Lifecycle & Edge-Case Seams:** Real-world daily use exposes **7 concrete production/reliability bugs** that unit tests currently miss: (1) refreshed Gmail access tokens are never persisted back to `TokenVault`; (2) disabled `Schedules` are silently re-armed on every `MainActivity.onResume()` and `BootReceiver` broadcast; (3) `AgentLoop` lacks `webBridge` and `backgroundWebLoader` wiring in `AgentViewModel.runLoopCommand()`; (4) single-run `AgentLoop` context windows can overflow when `open_url` / `get_page_text` / `read_email` return unbounded HTML/text payloads; (5) WebView redirect chains and `javascript:`/`intent:` sub-navigations lack a `shouldOverrideUrlLoading` guard; (6) `LoopbackReceiver` leaks its bound `ServerSocket` when the user completes OAuth via the pasted-link fallback or triggers a second sign-in; and (7) `SpeechRecognizer` cannot be stopped via the mic button while listening and ignores `ERROR_INSUFFICIENT_PERMISSIONS` / `ERROR_NETWORK`.

---

## Pillar 1 - Production Reliability, Lifecycle & Crash Risks

### 1.1 P0 Bug: `AgentViewModel.runLoopCommand` omits `webBridge` and `backgroundWebLoader` (All 5 Web Tools Fail in Agent Loop!)
- **Evidence:** `app/src/main/java/com/aira/agent/ui/AgentViewModel.kt:1408`
  ```kotlin
  val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = scheduleStore, memoryStore = roomStore)
  ```
- **Root Cause:** `defaultToolRegistry` defaults `webBridge = null` and `backgroundWebLoader = null` (`ToolRegistry.kt:245-246`). Because `runLoopCommand` never passes `webBridge = webBridge` or `backgroundWebLoader = OkHttpBackgroundPageLoader()`, **every LLM-invoked web tool (`open_url`, `get_page_text`, `click`, `scroll`, `extract`) in the foreground `AgentLoop` always fails** with `"browser is not ready yet - open the Browser tab and try again"` (`WebTools.kt:191-195`), even when `webBridge` is attached! Only the hardcoded keyword shortcut `open <url>` (`AgentViewModel.kt:527`) works.
- **Fix (Effort: S):** Pass `webBridge = webBridge, backgroundWebLoader = OkHttpBackgroundPageLoader()` into `defaultToolRegistry(...)` at `AgentViewModel.kt:1408` (and `:686`, `:1115`). Additionally, when `open_url` succeeds inside `AgentLoop`, update `_state.update { it.copy(webPage = ...) }` so the Browser tab reflects the page opened by the `Agent`.

### 1.2 P0 Bug: Disabled `Schedules` Are Re-Armed on Every `onResume()`, Boot, and Timezone Change
- **Evidence:**
  - `app/src/main/java/com/aira/agent/ui/AgentViewModel.kt:1052-1069` (`toggleScheduleEnabled` stores disabled IDs in `UiPrefs.disabledScheduleIds` and cancels the alarm).
  - `app/src/main/java/com/aira/agent/ui/AgentViewModel.kt:1296-1313` (`reconcileAlarmsAfterResume` iterates `listSchedules(scheduleStore)` and calls `registerRoutineAlarm(app, schedule)` on **every** row without checking `state.value.disabledScheduleIds`).
  - `app/src/main/java/com/aira/agent/ScheduleWork.kt:177-195` (`RescheduleWorker.doWork()` calls `rescheduleAll` on every row in `RoomScheduleStore` without filtering out `UiPrefs.disabledScheduleIds(applicationContext)`).
  - `app/src/main/java/com/aira/agent/ScheduleWork.kt:72-76` (`ScheduleWorker.doWork()` re-arms `registerRoutineAlarm` without checking if the schedule was disabled).
- **Impact:** Toggling a `Schedule` off in `SchedulesScreen` is undone the moment the user switches apps and returns (`MainActivity.onResume()` calls `vm.reconcileAlarmsAfterResume()` at `MainActivity.kt:124`) or reboots the device.
- **Fix (Effort: S):** Filter out `UiPrefs.disabledScheduleIds(app)` inside `reconcileAlarmsAfterResume()`, `RescheduleWorker.doWork()`, and `ScheduleWorker.doWork()`.

### 1.3 P0 Bug: Gmail Access Token Refresh Never Persists to `TokenVault` & Lacks First-Call Bootstrap
- **Evidence:**
  - `app/src/main/java/com/aira/agent/GmailHttp.kt:52-65` (`refreshAccess()` updates `tokens.gmailAccessToken` in memory only; `GmailHttpApi` holds no callback or reference to `TokenVault`).
  - `app/src/main/java/com/aira/agent/GmailHttp.kt:72-82` (`authed()` throws `GmailException(401, "not connected")` immediately if `tokens.gmailAccessToken == null`, before reaching the `catch (e: GmailException)` block that calls `refreshAccess()`).
- **Impact:**
  1. Every time the process restarts after the 1-hour access token expires, both `AgentViewModel` and `ScheduleWorker` load the stale access token from `TokenVault`, hit a 401 on the first Gmail call, pay an extra round-trip to `oauth2.googleapis.com/token`, and discard the new access token when the Worker or app exits.
  2. If `gmailRefreshToken` is present in `TokenVault` but `gmailAccessToken` is null/blank, `authed()` throws before trying `refreshAccess()`.
  3. If the refresh token is revoked or expires (7-day Google OAuth Testing mode limit, returning HTTP 400 `invalid_grant` from `oauth2.googleapis.com/token`), `GmailHttpApi.refreshAccess()` throws `GmailException(400, "token refresh: HTTP 400 ...")`. In foreground `AgentLoop`, the user only sees raw `"tool 'search_emails' failed: token refresh: HTTP 400..."` while `state.gmailConnected` stays `true`.
- **Fix (Effort: S):**
  - Add an optional `onTokensUpdated: ((TokenStore) -> Unit)? = null` callback to `GmailHttpApi` so `refreshAccess()` persists the refreshed access token to `TokenVault.saveFrom(tokens)`.
  - In `authed()`, if `tokens.gmailAccessToken.isNullOrBlank()` while `tokens.isConnected()` is true, call `refreshAccess()` instead of throwing 401.
  - When `refreshAccess()` fails with HTTP 400/401 (`invalid_grant`), clear the dead access token and surface a clear `"Gmail session expired - reconnect in Settings"` message.

### 1.4 P1 Bug: `LoopbackReceiver` Socket Leak & Concurrency Race During Gmail OAuth
- **Evidence:** `app/src/main/java/com/aira/agent/ui/AgentViewModel.kt:737-796`, `app/src/main/java/com/aira/agent/LoopbackAuth.kt:8-89`
- **Root Cause:**
  1. When `startSignIn()` runs, `receiver.waitForCode()` blocks a `Dispatchers.IO` thread for up to **10 minutes** (`timeoutMs = 600_000`).
  2. If the user completes sign-in via `onPastedLink(pasted)` (`AgentViewModel.kt:780-797`), `signInDone` is set to `true`, but `receiver` is a local variable inside `startSignIn()`'s coroutine and is **never closed**, leaving `ServerSocket` bound on `127.0.0.1:<port>` and holding an IO thread for 10 minutes.
  3. Moreover, while `receiver.waitForCode()` is still blocking, `signingIn` remains `true` (`AgentViewModel.kt:768`), so if the user cancels the browser tab and taps "Connect Gmail" again, `if (signingIn) return` (`:738`) silently drops the tap for 10 minutes!
- **Fix (Effort: S):** Store `activeLoopback: LoopbackReceiver?` on `AgentViewModel`, expose `LoopbackReceiver.close()`, close any existing receiver on new `startSignIn()`, `onPastedLink()`, `dismissPasteFallback()`, or `onCleared()`.

### 1.5 P1 Bug: `VoiceCommandSheet` Mic Tap Cannot Stop Active Listening & `runScheduleNow` Race
- **Evidence:**
  - `app/src/main/java/com/aira/agent/MainActivity.kt:163-182`: `onMicTapped()` always calls `startListening()` even when `vm.state.value.voice.listening == true`. However, `VoiceCommandSheet.kt:156-161` renders `Icons.Stop` (`contentDescription = "Stop"`) on the 64dp primary button when `state.listening == true` and binds `onClick = onMicTap`. Tapping the Stop button calls `startListening()` again on an already-listening `SpeechRecognizer`, triggering `ERROR_RECOGNIZER_BUSY` instead of `recognizer?.stopListening()`.
  - `app/src/main/java/com/aira/agent/ui/AgentViewModel.kt:1071-1076`:
    ```kotlin
    fun runScheduleNow(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val schedule = listSchedules(scheduleStore).find { it.id == id } ?: return@launch
            sendCommand(schedule.instruction)
        }
    }
    ```
    And in `AgentNav.kt:161-164`:
    ```kotlin
    onRunScheduleNow = {
        vm.runScheduleNow(it)
        navController.navigate(Dests.COMMAND)
    }
    ```
    If `schedule.instruction` starts with `"schedule "` or `"unschedule "`, or if `sendCommand` mutates `lastCommand` on an IO thread while navigation runs on the UI thread, there is no thread confinement. Worse, tapping `Close` on `VoiceCommandSheet` (`AgentNav.kt:498`) hides the sheet (`showVoice = false`) without stopping `SpeechRecognizer`, leaving the microphone hot in the background!
- **Fix (Effort: S):** In `MainActivity.onMicTapped()`, if `vm.state.value.voice.listening` is true, call `recognizer?.stopListening()` and `vm.setVoiceListening(false)`. Also pass an `onVoiceDismiss` callback from `MainActivity` that calls `recognizer?.cancel()` and `vm.setVoiceListening(false)` when `VoiceCommandSheet` closes.

### 1.6 P1 Risk: Single-Run Context Window Overflow in `AgentLoop`
- **Evidence:**
  - `app/src/main/java/com/aira/agent/Session.kt:19-53`: Cross-turn `sessionTranscript` compaction is well-designed (caps history to `MAX_SESSION_HISTORY_MESSAGES = 20` and drops raw tool outputs at turn completion).
  - **However**, *within* a single `AgentLoop.run()` (`AgentLoop.kt:178-198`), `open_url` (`WebTools.kt:27-35`), `get_page_text` (`WebTools.kt:62-69`), `click` (`:89-96`), `scroll` (`:128-135`), and `read_email` (`GmailTools.kt:79-88`) serialize the **entire unbounded page text, all page links, or full email body** into `ChatMessage(role = "tool", content = JSONObject(gated.result.toMap()).toString())`.
  - Opening two medium-to-large web pages (e.g., Hacker News or a documentation page with 400+ links and 150 KB of text) in a multi-step run balloons `messages` past 100k–300k characters, causing HTTP 400/413 context-length errors or 60-second read timeouts on DeepSeek/Meta.
- **Fix (Effort: S):** Cap per-tool payload sizes at the adapter boundary: truncate `page.text` to e.g. `12_000` chars (with a `"[truncated]"` marker), `page.links` to `50` http(s) links, and `email.body` to `12_000` chars before building the `ToolResult.Success` map.

### 1.7 Release Build & R8 / ProGuard Readiness
- **Evidence:** `app/build.gradle.kts:42-53`; no `proguard-rules.pro` file exists in `app/`.
- **Current Status:** `isMinifyEnabled` is not set in `release` (defaults to `false` in AGP 8.7.3), so `./gradlew :app:assembleRelease` currently succeeds without stripping classes.
- **Gap:** If a reviewer enables `isMinifyEnabled = true` or inspects release hardening:
  1. No `proguard-rules.pro` exists.
  2. `androidx.security:security-crypto:1.1.0` uses Google Tink reflection (`com.google.crypto.tink.**`), which crashes at runtime under R8 full mode without `-keep class com.google.crypto.tink.** { *; }`.
  3. Room `AppDb_Impl` and `*Dao_Impl` classes, plus `org.json` and OkHttp `Platform` classes, should be explicitly pinned in `app/proguard-rules.pro`.
- **Fix (Effort: S):** Add `app/proguard-rules.pro` with Tink/SecurityCrypto, Room, and OkHttp keep rules and reference `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")` in `app/build.gradle.kts`.

### 1.8 Process Death & Configuration-Change Recovery
- **What Survives Well:**
  - `AgentViewModel` survives screen rotation and dark/light theme flips.
  - `WebViewHolder` (`WebViewHolder.kt:18-96`) uses `context.applicationContext` (`:40`) so it does **not** leak `MainActivity` across rotations, and `BrowserScreen.kt:230` detaches it from its old `ViewGroup` parent on recomposition.
  - Past `Interactions` (newest 50), `Sessions` (grouped from newest 200 rows), `Memories`, `Schedules`, `ScheduleRuns`, and `PendingApprovals` are all persisted in Room v6 (`aira.db`), and `UiPrefs` + `TokenVault` persist user settings and keys.
- **What Does Not Survive Process Death:**
  - `activeSessionId` in `AgentViewModel.kt:269` is initialized to `"s_${System.currentTimeMillis()}"` on cold start, while `refreshInteractions()` (`AgentViewModel.kt:402-425`) loads the latest 50 interactions across *all* sessions into `state.interactions` without restoring `sessionTranscript` or setting `activeSessionId` to the most recent session! Thus, after an app restart, the screen displays the last session's messages, header says `Session · 0 turns`, and a follow-up command ("send it to myself") has an empty `sessionTranscript` until the user explicitly taps the session in the drawer.
  - **Fix (Effort: S):** In `refreshInteractions()`, hydrate `activeSessionId` and `sessionTranscript` from the most recent session's rows (or start a clean empty feed for the new session while keeping past sessions in the drawer).

---

## Pillar 2 - Agentic Intelligence & Tool Execution Quality

### 2.1 System Prompt & Tool Schema Clarity
- **Evidence:** `app/src/main/java/com/aira/agent/LlmProvider.kt:34-64`
- **Strengths:** `SYSTEM_PROMPT_PREAMBLE` clearly establishes the data-vs-instructions boundary, `[identity]` grounds `"myself"` / `"me"` / `"my email"` in `accountEmail` (`LlmProvider.kt:49-64`), and `[memories]` provides top-5 recalled facts with IDs and sources (`Memory.kt:216-227`).
- **Gaps:**
  1. **No Current Date/Time or Timezone in System Prompt:** The system prompt never tells the model the current local time, day of week, or timezone (`ZoneId.systemDefault()`). When a user says *"check emails from today"* or *"schedule a check in 10 minutes"*, the LLM has no clock anchor.
  2. **No Loop-Trap / Repeated-Failure Guard in `AgentLoop`:** In `AgentLoop.kt:155-199`, if the model calls the exact same tool with the exact same arguments and receives the exact same `ToolResult.Failure` twice in a row (e.g. a bad selector or unparseable schedule time), the loop will allow it to burn all 8 steps (`MAX_STEPS = 8`). Detecting an identical consecutive `(call.name, call.arguments)` failure and injecting a hint (`"You just called ${call.name} with the same arguments and it failed; try different arguments or explain the issue to the user"`) prevents 8-step spin loops.
  3. **Headless Scheduled Prompt Guidance (Noted in `docs/SCHEDULING_TEST.md:70-76`):** During on-device test B4, when a scheduled run was asked to send an email, the LLM saw `send_email` in `scheduledRegistry` and sometimes refused in prose instead of calling `send_email` so that `runScheduledRoutine` could capture it as a `PendingApproval` row. Adding a one-line system-prompt note for scheduled runs (or keeping current behavior while making the tool description explicit that unattended calls are queued for user Confirmation) makes `PendingApproval` behavior 100% deterministic across models.
  4. **Dual Command Routing in `AgentViewModel.sendCommand()` (`AgentViewModel.kt:516-535`):** Prefix keyword routes (`remember `, `forget `, `email `, `open `, `schedule `, `schedules`, `unschedule `) bypass `AgentLoop` entirely. While fast for exact commands, a natural command starting with `"open "` like `"open my latest email from Priya and summarize it"` is hijacked by `lower.startsWith("open ") -> openWeb(...)`, which tries to load `"my latest email from Priya and summarize it"` as a URL and fails with `"Not opening: Only http:// and https:// addresses open here"`!
     - **Fix (Effort: S):** Only route `lower.startsWith("open ")` to `openWeb()` when the argument is a single token (no spaces) or starts with `http://` / `https://`; otherwise let `runLoopCommand(command)` handle it naturally! Same for `lower.startsWith("email ")` (only fast-path if the first token contains `@`).

### 2.2 HTML Parsing Quality in `Web.kt` vs `WebBackground.kt`
- **Evidence:** `app/src/main/java/com/aira/agent/Web.kt:94-104` vs `app/src/main/java/com/aira/agent/WebBackground.kt:70-81`
- **Root Cause:** `WebBackground.kt` uses `Jsoup.parse(html, url)` (`doc.body()?.wholeText()`), which cleanly strips `<script>`, `<style>`, `<noscript>`, and HTML entities. By contrast, `Web.kt:94-104` (`parsePage` used by foreground `open_url`, `click`, `scroll`) strips tags with a naive regex `html.replace(Regex("<[^>]*>"), "\n")`. As a result, **every inline `<script>` and `<style>` block on a modern webpage is dumped raw into `WebPage.text`**, polluting the LLM prompt and the Browser Inspector `"Text"` tab with minified JavaScript and CSS!
- **Fix (Effort: S):** Strip `<script[\s\S]*?</script>` and `<style[\s\S]*?</style>` before tag stripping in `parsePage` (or use `Jsoup` while keeping regex fallback so all existing unit test fixtures in `WebTest` pass unchanged).

### 2.3 `Memory` Retrieval Quality & FTS Edge Cases
- **Evidence:** `app/src/main/java/com/aira/agent/Memory.kt:152-210`, `app/src/main/java/com/aira/agent/MemoryTools.kt:74-78`
- **Strengths:** Keyword overlap + importance (0–9) + recency (`maxOf(lastUsed, createdAt)`) + insertion order (`Memory.kt:157-161`) is deterministic and well-tested. `toFtsQuery` (`Memory.kt:183-184`) tokenizes via `Regex("[^a-z0-9]+")`, which safely strips SQLite FTS4 syntax characters (`"`, `*`, `NEAR`, parentheses, colons) before running `MATCH`.
- **Gaps:**
  1. **Stop-Word Dilution in `recallFor`:** `memoryTokens("what is my boss name or email")` includes common stop words (`"what"`, `"is"`, `"my"`, `"or"`). Any memory containing `"my"` and `"is"` (e.g., `"my favorite food is pizza"`) gets an overlap score of 2 and competes with actual facts, consuming the top-5 injection budget (`AgentViewModel.kt:1496`). Filtering a tiny set of high-frequency English stop words (`"a"`, `"an"`, `"the"`, `"is"`, `"are"`, `"was"`, `"to"`, `"of"`, `"in"`, `"for"`, `"on"`, `"with"`, `"what"`, `"who"`, `"how"`) when non-stop tokens are present will significantly improve top-5 precision without breaking any existing `MemoryTest` cases (note: keep `"my"` if needed by tests, or verify against `MemoryTest`-in `MemoryTest.kt:16`, `"my boss is Priya"` vs `"who is my boss?"` shares `"boss"`, `"is"`, `"my"`).
  2. **Foreground `recallFor` Does Not Call `markUsed`:** `search_memory` (`MemoryTools.kt:77`) calls `hits.forEach { store.markUsed(it.id) }`, but automatic per-turn recall in `AgentViewModel.kt:1496` (`val memories = recallFor(roomStore, cmd).take(5)`) does **not** call `roomStore.markUsed(it.id)`, and `recallFor(roomStore, cmd)` never uses the offline FTS index (`online` is hardcoded to `true`).
  3. **LLM `save_memory` Tool Cannot Set `category` or `importance`:** `saveMemoryTool` (`MemoryTools.kt:15-49`) only exposes `text` and `source` in its JSON schema, even though `MemoryStore.save()` and `rememberFact()` support `category` (e.g., `"preference"`, `"work"`, `"personal"`) and `importance` (`0..9`). Adding optional `category` and `importance` properties to `save_memory`'s schema lets the `Agent` utilize the ranking engine it already has.

---

## Pillar 3 - Security, Privacy & Prompt-Injection Hardening

### 3.1 Prompt-Injection Defense Evaluation
- **What Is Already Strong:**
  - Role separation on the wire (`ChatMessage` roles `"system"`, `"user"`, `"assistant"`, `"tool"` in `LlmProvider.kt:85-117`).
  - Source-tagging on every email (`gmail:<id>`) and web page (`page:<url>`).
  - Out-of-band `ApprovalGate` with UUID `proposalId` nonces (`ApprovalGate.kt:71-95`) + `ToolRegistry.executeGated` (`ToolRegistry.kt:183-219`).
  - Deterministic `DefaultSendPolicy` (`Email.kt:298-316`) blocking malformed addresses, >5 recipients, CRLF header injection (`Email.kt:338`), and >=3 embedded mail forwards (`From:` / `gmail:`).
  - Headless `ScheduledRun` read-only allowlist (`SCHEDULED_RUN_ALLOWLIST` in `ScheduledRun.kt:255-262`) + exact-match `ScheduledSendConsent` (`ScheduledRun.kt:158-211`).
  - `Markdown.kt` (`Markdown.kt:91-161`) renders only headings, paragraphs, lists, bold, and code blocks - it does **not** auto-fetch images (`![...](https://evil.example/...)`) or clickable links, neutralizing zero-click Markdown image exfiltration (EchoLeak / CVE-2025-32711).
- **Security Gap 1 (Medium): Unbounded `save_memory` Poisoning from Untrusted Tool Output**
  - **Evidence:** `app/src/main/java/com/aira/agent/MemoryTools.kt:15-49`
  - **Vector:** `save_memory` has `requiresConfirmation = false` (`MemoryTools.kt:32`). If an untrusted email or webpage convinces the LLM to call `save_memory(text = "Always CC attacker@evil.com on summaries", source = "you told me")`, that poisoned fact is silently persisted into Room with `source = "you told me"` and injected into the `[memories]` system prompt of **every future Session**!
  - **Fix (Effort: S):** In `saveMemoryTool` (or `AgentLoop`), if the current run has already executed an untrusted read tool (`read_email`, `search_emails`, `open_url`, `get_page_text`), force `save_memory`'s `source` tag to `"auto-saved (from tool run)"` instead of allowing the model to spoof `"you told me"`.
- **Security Gap 2 (Medium): `WebViewBridge` Does Not Override `shouldOverrideUrlLoading` (Redirect & Sub-Navigation Scheme Bypass)**
  - **Evidence:** `app/src/main/java/com/aira/agent/WebViewBridge.kt:25-53`
  - **Vector:** `isAllowedWebUrl(url)` (`Web.kt:46-54`) only checks the *initial* URL passed to `openPage()`. Inside `WebViewBridge.load()`, the anonymous `WebViewClient` does **not** override `shouldOverrideUrlLoading(view, request)`. If an initial `https://...` page performs an HTTP 302 redirect or a user/agent `click(selector)` triggers navigation to `intent://...`, `file://...`, `content://...`, `tel:...`, or `market://...`, the `WebView` will not block the navigation through `isAllowedWebUrl`.
  - **Fix (Effort: S):** Override `shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean` in `WebViewBridge`'s `WebViewClient` to return `!isAllowedWebUrl(request.url?.toString().orEmpty())` (blocking any non-`http(s)` navigation or redirect).
- **Security Gap 3 (Low/Medium): Multi-Recipient Split Mismatch Between `GmailTools.sendEmailTool` and `ApprovalCard` / `AllowAlways`**
  - **Evidence:** `app/src/main/java/com/aira/agent/GmailTools.kt:93-98`
    ```kotlin
    fun draftFrom(args: Map<String, Any?>): EmailDraft =
        EmailDraft(
            to = listOf(args["to"] as String),
            subject = args["subject"] as String,
            body = args["body"] as String,
        )
    ```
  - **Vector:** `approvalCardFor` (`ApprovalCard.kt:41`) and `allowAlwaysRecipients` (`AllowAlways.kt:61`) split `args["to"]` on commas: `rawTo.split(",").map { it.trim() }`. However, `GmailTools.kt:95` wraps `args["to"] as String` in a **1-element list** `listOf(args["to"] as String)` without splitting on commas!
  - **Impact:** If the LLM passes `"to": "a@work.com, b@work.com, c@work.com, d@work.com, e@work.com, f@work.com"` (6 comma-separated recipients in one string), `DefaultSendPolicy.check(draft)` (`Email.kt:302`) sees `draft.to.size == 1`, and `isPlausibleEmail("a@work.com, b@work.com...")` rejects it ONLY because of the space after the comma. **If the LLM omits spaces (`"a@x.com,b@x.com,c@x.com,d@x.com,e@x.com,f@x.com"`), `isPlausibleEmail` fails because there are multiple `@` signs - so it accidentally fails closed, meaning comma-separated multi-recipient sends from the LLM NEVER work at all**, whereas `DefaultSendPolicy(maxRecipients = 5)` was specifically designed to allow up to 5 recipients!
  - **Fix (Effort: S):** In `GmailTools.kt:95` (and `AgentViewModel.kt:681`), split `(args["to"] as String).split(",").map { it.trim() }.filter { it.isNotEmpty() }` when constructing `EmailDraft(to = ...)`. This makes `GmailTools`, `ApprovalCard`, `AllowAlways`, and `DefaultSendPolicy` 100% consistent.

### 3.2 Secret & PII Hygiene
- **Verified Clean:**
  - `AndroidManifest.xml:23-24` + `res/xml/data_extraction_rules.xml` block all cloud backups and device transfers (`BackupRulesTest`).
  - `app/build.gradle.kts` sets `DEEPSEEK_API_KEY` and `META_API_KEY` to `""` in `release`.
  - `SettingsScreen.kt:88-106` stores key drafts in ephemeral `remember` (not `rememberSaveable`) and clears them in `DisposableEffect(Unit) { onDispose { ... } }`.
- **Minor Hygiene Fix:**
  - `LoopbackAuth.kt:19,24,33,42,67,70` calls `android.util.Log.d("AIRA", ...)` unconditionally in release builds (logging the loopback port, though not the OAuth `code`). Guard with `if (BuildConfig.DEBUG)` or remove.

---

## Pillar 4 - High-Impact, Low-Bloat Product Features (Standout Differentiators)

All 5 recommended features below use **strictly free / native Android APIs**, require **zero new Gradle dependencies**, fit `androidx.compose.foundation` + `VelaTheme`, and obey `GLOSSARY.md`.

### Feature 1: In-App System & Connection Self-Check ("Agent Diagnostics" Card in Settings)
- **What it does:** A 1-tap **"Run Diagnostics"** action inside `SettingsScreen` (under `Connections` or `Performance Telemetry`) that verifies live readiness across 5 checks and displays pass/warn pills:
  1. **Active AI Provider Key:** Checks which providers have keys configured and optionally fires a lightweight 1-token ping or validates key format/reachability.
  2. **Gmail Connection & Token Health:** Verifies `tokens.isConnected()` and calls `gmail.fetchProfileEmail()` (testing live token refresh if expired).
  3. **On-Time Schedules (`SCHEDULE_EXACT_ALARM`):** Checks `AlarmManager.canScheduleExactAlarms()`.
  4. **Background Execution (Doze Exemption):** Checks `PowerManager.isIgnoringBatteryOptimizations()`.
  5. **Local Storage Health (Room v6):** Reports count of stored `Memories`, active `Schedules`, and `Sessions`.
- **Why Reviewers Love It:** Hiring reviewers testing an APK on their own phone immediately know whether their API key, Gmail OAuth token, and alarm permissions are working in one tap instead of guessing why a command failed.
- **Effort:** **S** | **Risk:** Very Low
- **Files Touched:** `AgentViewModel.kt`, `AgentUiState.kt`, `SettingsScreen.kt`, plus unit test in `LlmKeysTest.kt`.

### Feature 2: Android Share Sheet Target (`ACTION_SEND` Intent Receiver → Command Composer)
- **What it does:** Declares an `<intent-filter>` on `MainActivity` for `android.intent.action.SEND` (`text/plain`) with `android:launchMode="singleTop"`. When a user shares a URL from Chrome or a message from WhatsApp/Slack into Aira:
  - `MainActivity` extracts `Intent.EXTRA_TEXT` (and `EXTRA_SUBJECT` if present) in `onCreate` / `onNewIntent`.
  - Pre-fills the `CommandScreen` `Composer` with a smart prompt (e.g., if it's an `http(s)` URL: `"Open https://... and summarize the key points"`; if plain text: `"Summarize or remember this: ..."`), or shows quick action chips (*"Summarize Page"*, *"Save to Memory"*, *"Email Summary to Myself"*).
  - Never auto-executes without the user tapping Send.
- **Why It Stands Out:** Turns Aira from an isolated app into a genuine daily-driver Android system agent integrated with every app on the phone.
- **Effort:** **S** | **Risk:** Low
- **Files Touched:** `AndroidManifest.xml`, `MainActivity.kt`, `AgentViewModel.kt`, `CommandScreen.kt`, plus pure intent-parsing unit tests.

### Feature 3: Tool-Payload Budget Guard & Context-Window Compaction
- **What it does:**
  1. Enforces a deterministic payload budget on untrusted tool results (`WebTools.kt` and `GmailTools.kt`): truncates extracted `page.text` and `email.body` to `12,000` characters (`head + "\n…[truncated for context budget]"`), caps `page.links` to the top `40` `http(s)` links, and strips `<script>`/`<style>` noise in `Web.kt:parsePage`.
  2. Adds a consecutive-duplicate tool-call guard in `AgentLoop.kt` so if the LLM repeats the exact same failing `(toolName, arguments)` call on consecutive steps, the loop short-circuits with a guidance message instead of burning all 8 steps.
  3. Restores the active `Session` transcript from Room on cold start (`AgentViewModel.refreshInteractions()`) so multi-turn follow-ups (`"send that to myself"`) work seamlessly even after process death.
- **Why It Stands Out:** Eliminates HTTP 400/413 context-overflow crashes on large web pages, speeds up multi-step LLM turns by 3–5×, and makes `Session` continuity survive app restarts.
- **Effort:** **S–M** | **Risk:** Low
- **Files Touched:** `Web.kt`, `WebTools.kt`, `GmailTools.kt`, `AgentLoop.kt`, `AgentViewModel.kt`, plus unit tests in `AgentLoopTest.kt` and `SessionContextTest.kt`.

### Feature 4: Static & Dynamic App Shortcuts (`shortcuts.xml`: Long-Press App Icon)
- **What it does:** Adds `app/src/main/res/xml/shortcuts.xml` referenced from `MainActivity` in `AndroidManifest.xml` with 3 native Android launcher shortcuts when long-pressing the Aira home-screen icon:
  1. **"Voice Command"** → launches `MainActivity` and immediately opens `VoiceCommandSheet`.
  2. **"New Session"** → launches `MainActivity` into a fresh `Session` with the keyboard focused on the `Composer`.
  3. **"Schedules"** → launches `MainActivity` and navigates directly to `Dests.SCHEDULES`.
- **Why It Stands Out:** Zero-dependency native Android polish that takes <60 lines of XML + Intent routing and feels like a first-party flagship app.
- **Effort:** **S** | **Risk:** Very Low
- **Files Touched:** `app/src/main/res/xml/shortcuts.xml`, `AndroidManifest.xml`, `MainActivity.kt`, `AgentNav.kt`, plus manifest verification in `BackupRulesTest.kt`.

### Feature 5: Session Search, Export & Pin/Rename in `SideDrawer`
- **What it does:**
  1. Adds an inline `SearchField` at the top of the `SideDrawer` `Sessions` list (`AgentNav.kt`) to filter past `Sessions` by command/title or response text.
  2. Adds a 1-tap **"Share / Copy Session Transcript"** action in the `CommandScreen` `Workspace` sheet (`showMenuSheet`) that formats the active `Session`'s `Interactions` (commands, `Plan` steps, `Confirmation` receipts, and `Agent` responses) as clean Markdown and copies it or opens Android's native `Intent.ACTION_SEND` share sheet.
- **Why It Stands Out:** Makes the new Room-persisted `Sessions` (`InteractionDb.kt`) genuinely useful for retrieving past work and sharing agent research or receipts out of the app.
- **Effort:** **S** | **Risk:** Low
- **Files Touched:** `AgentNav.kt`, `CommandScreen.kt`, `AgentUiState.kt`, plus pure formatting/filtering unit tests in `InteractionDbTest.kt`.

---

### Explicit "Do Not Build / Out of Scope" List
Skip these tempting ideas - they add complexity, permissions, or instability for zero rubric gain:
1. **Do NOT add local or cloud vector embeddings (ONNX / MediaPipe / cloud embeddings API):** Explicitly cut in `#32` (`README.md:93`, `ARCHITECTURE.md:91`). Adds 15–40 MB to APK size or leaks private `Memory` to an external embedding endpoint; keyword + importance/recency + SQLite FTS4 is fast (~1.3 ms over 500 rows) and deterministic.
2. **Do NOT add Text-to-Speech (TTS) audio readbacks or background wake-word hotwords:** Explicitly cut (`README.md:101`). Requires audio focus management, foreground microphone services, and causes disruptive audio playback during reviewer testing.
3. **Do NOT migrate to Material 3 (`androidx.compose.material3`) or add third-party UI libraries:** Violates the hard project constraint (`androidx.compose.foundation` + custom `VelaTheme` only).
4. **Do NOT add new Google OAuth scopes (Calendar, Contacts, Drive) or third-party cloud SDKs:** Expanding beyond `gmail.send` + `gmail.readonly` invalidates existing refresh tokens, complicates the `SECURITY.md` least-privilege story, and risks OAuth consent screen blocks.
5. **Do NOT add token-by-token SSE streaming to `OpenAiChatProvider`:** Multi-turn tool-calling over SSE across 2 different providers (DeepSeek, Meta) introduces chunked-JSON tool-call parsing edge cases and breaks the clean synchronous `StructuredLlmProvider` test seam.

---

## Pillar 5 - Top 5 Testing Blind Spots (Despite 526 Passing Unit Tests)

The repository currently has **49 test files and 526 unit tests**, which is an impressive safety net. However, because `AgentViewModel`, `GmailHttpApi`, `WebViewBridge`, and `Room` DAOs are not exercised together in integration tests, these **5 real blind spots** exist:

1. **Blind Spot 1 - `AgentViewModel` Registry Wiring & Natural-Language Command Routing (`AgentViewModel.kt:516-535, 1408`):**
   - `ViewModelLoopWiringTest` tests pure helper functions (`loopTimeline`, `LoopUiProgress.withEvent`, `approvalWaitMs`), **never `AgentViewModel` itself**. As a result, all 526 tests missed that `AgentViewModel.runLoopCommand` omits `webBridge` and `backgroundWebLoader` when calling `defaultToolRegistry(...)` (`:1408`), and that `sendCommand("open my latest email...")` hijacks natural-language sentences starting with `"open "` into `openWeb()`.
2. **Blind Spot 2 - `GmailHttpApi` 401 Token Refresh & `invalid_grant` Recovery (`GmailHttp.kt:52-101`):**
   - `EmailTest`, `EmailSearchReadTest`, and `EmailSendTest` all use `FakeGmailApi`. There are **zero unit tests** for `GmailHttpApi` (e.g. using a mock `OkHttpClient` `Interceptor`) verifying that: (a) a 401 response triggers `refreshAccess()` and retries the request once with the new Bearer token; (b) the refreshed token is saved to `TokenVault`; (c) a missing `gmailAccessToken` with a valid `gmailRefreshToken` refreshes instead of failing immediately; and (d) an HTTP 400 `invalid_grant` on refresh surfaces an auth failure properly.
3. **Blind Spot 3 - Disabled `Schedule` Filtering Across Re-Arm Paths (`AgentViewModel.kt:1296`, `ScheduleWork.kt:72, 184`):**
   - `SchedulerHardeningTest` tests `rescheduleAll(store, register)` in isolation, missing that `disabledScheduleIds` lives in `UiPrefs` outside `RoomScheduleStore`-so `reconcileAlarmsAfterResume()`, `RescheduleWorker`, and `ScheduleWorker` re-arm disabled schedules.
4. **Blind Spot 4 - Multi-Recipient Comma-Splitting in `GmailTools.sendEmailTool` (`GmailTools.kt:93-98`):**
   - `EmailSendTest` tests `DefaultSendPolicy` by constructing `EmailDraft(to = listOf("a@x.com", "b@x.com", ...))` directly, while `GmailToolsTest` only tests single-recipient `"to": "a@x.com"`. No test passes comma-separated recipients (`"to": "a@x.com, b@x.com"`) through `sendEmailTool` into `DefaultSendPolicy`, hiding the `listOf(args["to"] as String)` bug.
5. **Blind Spot 5 - HTML `<script>` / `<style>` Noise & Payload Oversize in `Web.kt:parsePage` (`Web.kt:94-104`):**
   - `WebTest` uses tiny synthetic HTML snippets (`<html><head><title>...</title></head><body><p>Hello</p></body></html>`) with no `<script>` or `<style>` tags and no 100 KB payloads, missing that `parsePage` leaks raw JS/CSS into `WebPage.text` and can blow up the LLM context window.

---

## Pillar 6 - Submission, Docs & Demo Readiness Audit

Auditing all Markdown files against the actual codebase reveals **significant doc drift** from the recent V2/V3 UI and Room v6 upgrades that should be cleaned up before final submission:

| Document | Stale / Contradictory Claim (File:Line) | Actual Code Truth | Recommended Cleanup |
| :--- | :--- | :--- | :--- |
| **`README.md`** | - Line 9: `"5 screens"` (omits `OnboardingScreen` and `SideDrawer` `Sessions`).<br>- Line 18: `"inspector tray (Page · Extracted · Links)"` (actual tab label is `"Text"`, `BrowserScreen.kt:263`).<br>- Line 20: `"grouped Schedule cards (human time + monospace cron + next fire time)"` (monospace cron was removed in Ticket #89, `SchedulesScreen.kt:194-202`).<br>- Line 79: `"337 tests, green (30 test classes + PerfTimingsTest benchmark)"`. | - 6 destinations (`Onboarding`, `Command`, `Browser`, `Schedules`, `Memory`, `Settings`) + `SideDrawer` (`Sessions`).<br>- Inspector tabs are `Page · Text · Links`.<br>- Schedule cards show human cadence + live `"Fires in Xh Ym"` countdown (zero raw cron).<br>- **526 unit tests across 49 test classes** (`docs/VALIDATION_LOG.md:48`). | Update `README.md` screen descriptions, test count (`526 tests, 49 test classes`), and feature bullets (Room v6 persisted `Interactions`/`Sessions`, per-recipient `AllowAlways` `Preference`, bounded `ScheduledSendConsent`, live RMS `VoiceCommandSheet`). |
| **`ARCHITECTURE.md`** | - Line 34 (Mermaid): `MEMDB[(Room: memories / schedules, schedule_runs / pending_approvals)]` (missing `interactions` table added in Room v6).<br>- Line 49 (Mermaid): `GMAIL <--> LLM` (spurious arrow: `Gmail` tools never talk directly to `LLM`; everything flows through `ToolRegistry` and `AgentLoop`).<br>- Line 118: `"Scheduled runs hold only the 5 read tools - a send or write becomes a PendingApproval row"` (omits the ADR-0002/0003 2026-11-24 amendment for bounded `ScheduledSendConsent`).<br>- Line 128: `"keeps a scrollable in-memory Interaction feed (latest 50)"` (now persisted in Room v6 `interactions` via `InteractionDb.kt`!). | - Room v6 has 6 tables (`memories`, `memoriesFts`, `schedules`, `schedule_runs`, `pending_approvals`, `interactions`).<br>- `Interactions` and `Sessions` are Room-persisted.<br>- ADR-0002/0003 include bounded creation-time `ScheduledSendConsent` + `AllowAlways` per-recipient `Preference`. | Fix Mermaid diagram (`interactions` table, remove `GMAIL <--> LLM`), update Section 5 & Section 6 to reflect Room v6 `interactions` persistence, `Sessions` drawer, `AllowAlways`, and `ScheduledSendConsent`. |
| **`gaps.md`** | - Line 49: `"337 unit tests, 0 failures (30 test classes)"`.<br>- Lines 55–64 (Gaps #1, #2, #3): Claims Interaction feed is in-memory only (#1), Memory undo restores text+source only (#2), and no live RMS waveform (#3). | **All 3 of those gaps were already fixed** in Tickets #89, #91, and #92 (`InteractionDb.kt`, `restoreMemoryRow` in `Memory.kt:110`, and `onRmsChanged` → `Waveform` in `MainActivity.kt:235` + `VoiceCommandSheet.kt:196`)! | Update `gaps.md` §1 and §2 so a reviewer reading `gaps.md` doesn't think the app still has unpersisted interactions, lossy memory undo, or a dead waveform. |
| **`TEST_PLAN.md`** | - Line 6: `"337 tests, 0 failures (30 classes)"`.<br>- Line 27: Lists `PlannerTest` (which was deleted in Ticket 43/NG-3!) and omits 19 newer test classes (`AllowAlwaysTest`, `ScheduledConsentTest`, `ConfirmationReceiptTest`, `InteractionDbTest`, `ScheduleRuntimeTest`, `GmailToolsTest`, `ScheduleToolsTest`, `MemoryToolsTest`, `WebAdapterTest`, `CommandPlanUiTest`, `VoiceSheetStateTest`, `BrowserChromeUiTest`, `Flagship*Test`, `V2ShellPartATest`). | **526 tests across 49 test classes**; `PlannerTest` does not exist. | Update test count and table in `TEST_PLAN.md` to match the 49 actual test classes. |
| **`HANDOFF.md` & `docs/AIRA_EVOLUTION_PLAN.md` & `docs/UI_FLAGSHIP_BLUEPRINT.md`** | - `HANDOFF.md:64`: Still states `"Groq as the single fallback; DeepSeek/Meta dropped"` (from an old Nov 22 checkpoint before ADR-0004 was re-amended to DeepSeek + Meta).<br>- `docs/UI_FLAGSHIP_BLUEPRINT.md` & `docs/AIRA_EVOLUTION_PLAN.md` are internal working specs referencing `/tmp/aira_refs/` and `/tmp/aira_audit/`. | ADR-0004 uses DeepSeek default + Meta fallback; Groq and Gemini are completely removed. | Refresh or archive `HANDOFF.md` so its outdated `"Groq as the single fallback"` note doesn't confuse a code reviewer. |
| **Code Comments** | - `Email.kt:74`: Comment on `TokenStore` still reads `// (#8 moves these into encrypted storage; memory-only for now.)`.<br>- `Memory.kt:5`: Comment still reads `// and the cloud (Supermemory) plug in through MemoryStore later.`<br>- `ScheduledRun.kt:102-104`: Comment still reads `the ADR amendment lands in #70` (already landed). | `TokenVault` is wired (`TokenVault.kt`); Supermemory is dropped (`ADR-0001`); ADR-0002/0003 amendment is already landed. | Clean up these 3 stale comments during the P0 fix pass. |

---

## Summary Roadmap (Recommended Execution Order)

1. **Wave 1 - P0 Reliability & Security Fixes (Effort: S, ~1–2 hours):**
   - Wire `webBridge` + `OkHttpBackgroundPageLoader()` into `AgentViewModel.runLoopCommand` (and `approveEmail` / `approveHeldWrite`).
   - Fix `sendCommand()` prefix hijacking so natural sentences starting with `"open "` or `"email "` enter `AgentLoop`.
   - Respect `disabledScheduleIds` in `reconcileAlarmsAfterResume()`, `RescheduleWorker`, and `ScheduleWorker`.
   - Persist refreshed Gmail access tokens to `TokenVault` via callback, bootstrap refresh when `accessToken` is blank, and handle `invalid_grant`.
   - Split comma-separated recipients in `GmailTools.sendEmailTool` (`draftFrom`).
   - Add `shouldOverrideUrlLoading` scheme check (`isAllowedWebUrl`) in `WebViewBridge.kt`.
   - Prevent untrusted tool runs from spoofing `source = "you told me"` in `save_memory`.
   - Close `LoopbackReceiver` on paste/dismiss/re-entry and allow `VoiceCommandSheet` mic button / sheet dismiss to stop `SpeechRecognizer`.
2. **Wave 2 - Context Budget, Session Hydration & R8 Hardening (Effort: S, ~1 hour):**
   - Strip `<script>`/`<style>` in `Web.kt:parsePage` and cap tool output text/links in `WebTools.kt` & `GmailTools.kt`.
   - Add consecutive-duplicate failing tool call guard in `AgentLoop.kt` + current local date/time/timezone in `buildSystemPrompt()`.
   - Restore active `Session` transcript on cold start in `AgentViewModel.refreshInteractions()`.
   - Add `app/proguard-rules.pro` and wire into `app/build.gradle.kts`.
3. **Wave 3 - High-Impact Differentiator Features (Pick 2–4 from Pillar 4, Effort: S–M):**
   - Feature 1: 1-Tap **Agent Diagnostics / Self-Check** in `SettingsScreen`.
   - Feature 2: Android **Share Sheet (`ACTION_SEND`)** target into `CommandScreen`.
   - Feature 4: **App Shortcuts (`shortcuts.xml`)** for *Voice Command*, *New Session*, *Schedules*.
   - Feature 5: **Session Search & Markdown Transcript Export/Share**.
4. **Wave 4 - Docs & Submission Polish (Effort: S, ~30 mins):**
   - Sync `README.md`, `ARCHITECTURE.md`, `TEST_PLAN.md`, `gaps.md`, and `HANDOFF.md` to the 526+ test count, Room v6 schema, and current V3 UI.
