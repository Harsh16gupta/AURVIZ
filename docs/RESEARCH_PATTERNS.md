# Research patterns: agent loops, approval, memory, schedules, injection defense, tests

Date: 2026-11-22. Method: `context.dev` MCP (`web-search`, `web-scrape`, `web-answers`)
via the `context` server in `~/.config/opencode/opencode.json`, plus `gh api` for
stars/dates/licenses and raw files. Code references are HEAD at `07f2930`
(gaps.md in `gaps.md` was written against `3fcfbff`, so much of its P0 is now done —
see §4). Claims I could not verify are marked **[UNVERIFIED]**.

## 1. Projects

| # | Project | Stars | Last push | License | Maintained | Borrow |
|---|---------|-------|-----------|---------|------------|--------|
| 1 | https://github.com/google/adk-kotlin | 309 | 2026-11-21 | Apache-2.0 | yes | `requireConfirmation` gate shape; boolean vs dynamic vs advanced confirmation; remote-confirmation REST pattern |
| 2 | https://github.com/JetBrains/Koog | 4668 | 2026-11-10 | Apache-2.0 | yes | Graph strategy `singleRunStrategy` loop shape; chat-memory + SQL persisters; `agents-test` module pattern |
| 3 | https://github.com/googlesamples/mlkit (`android/genai` demo) | 4310 | 2026-10-26 | Apache-2.0 | yes | `HitlConfirmationTool` decorator; `maxTurns` cap (default 6); three tool modes incl. scoped allowlist |
| 4 | https://github.com/Liquid4All/cookbook (`examples/AndroidAgent`) | 2794 | 2026-11-17 | **none found (no LICENSE file)** | yes | Minimal loop shape only — **do not copy code** (unlicensed) |
| 5 | https://github.com/langchain4j/langchain4j | 13434 | 2026-11-22 | Apache-2.0 | yes | `ChatMemoryStore` seam; guardrail/approval-in-tool-code idiom (via docs, not verified in code) |
| 6 | https://github.com/ghost-in-the-droid/android-agent | 431 | 2026-11-15 | MIT | yes | HITL-at-checkpoint pattern (person solves captcha, agent continues); skill-scoped tool surface |

Looked at and rejected: `Mdemon0088/Android-AI-Agent` (0 stars, no license, toy);
`looper42/mobile_agent` (10 stars, too small to learn from); `AAswordword/Operit`,
`yashab-cyber/opendroid` (nonstandard/unclear licensing, accessibility-hack architecture
we should not imitate); `google/adk-java` (1779 stars, Apache-2.0 — sibling of #1,
skipped as duplicative under the 6-project cap).

## 2. Patterns (evidence per project)

### Q1. Tool-calling loop: max steps, timeouts, error handling

- **adk-kotlin**: event loop is framework-owned (`adk.dev/runtime/event-loop/`).
  Evidence: `core/.../agents/LlmAgent` + `examples/.../hitl/HitlDemoAgent.kt` (KSP-generated
  `generatedTools()`). No hand-rolled loop to copy; the lesson is the confirmation-hook
  placement (see Q2), not loop mechanics.
- **Koog**: graph strategy — `singleRunStrategy(parallelTools)` in
  `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/AIAgentSimpleStrategies.kt`:
  nodes `nodeLLMRequest → nodeExecuteTools → nodeLLMSendToolResults`, edges loop back on
  tool-calls, finish on text message. Retries/fault-tolerance are framework features
  (product docs), exact caps **[UNVERIFIED in code]**.
- **MLKit demo**: framework-owned `AgentRunner` with `AgentConfig.maxTurns` (default 6) in
  `android/genai/.../repository/AgentRepository.kt`. Single integer cap, no per-step timeout
  visible in the demo config **[UNVERIFIED beyond config]**.
- **Liquid AndroidAgent**: hand-rolled `for (iteration in 1..maxIterations)` loop in
  `examples/AndroidAgent/app/src/main/kotlin/ai/liquid/koogleapsdk/agents/common/Agent.kt`
  — closest to ours: appends `role:"tool"` messages with `tool_call_id`, breaks on plain-text
  answer, errors on overrun, tool errors returned as text so the model can retry
  (`callTool` comment: "Errors go back to the model as the result, so it can retry").
  No timeouts, no approval, no persistence visible.
- **langchain4j**: `ChatMemoryStore` interface
  (`langchain4j-core/.../store/memory/chat/ChatMemoryStore.java`) + tutorial
  `docs.langchain4j.dev/tutorials/chat-memory/`; loop mechanics live in user code;
  HITL is "ask-permission-first-thing-in-tool-code" per JAVAPRO hands-on (blog).
- **android-agent (ghost)**: checkpoint HITL over ADB-driven tools (repo description;
  internals **[UNVERIFIED — not read]**).

What we already match: our `AgentLoop` (`app/src/main/java/com/aurviz/agent/AgentLoop.kt:113`)
has max 8 steps, per-step 90s + total 5min timeouts, structured `{ok:false, error, retryable}`
results, `tool_call_id` matching — i.e. Liquid's shape plus the timeouts/gating Liquid lacks.

### Q2. Human approval: code gate vs prompt plea

- **adk-kotlin (borrow this)**: three tiers, all enforced in code, primary source
  `https://adk.dev/tools-custom/confirmation/` (scraped 2026-11-22): (a) boolean
  `@Tool(requireConfirmation = true)` — `core/.../annotations/Tool.kt`, demo
  `HitlDemoAgent.kt` (`initiateWarpJump` gated, `scanPlanet` not); (b) dynamic threshold via
  `ToolContext.requestConfirmation(hint)` inside the tool (Kotlin flag is compile-time const,
  so conditions live in the function body); (c) advanced structured payload
  (`payload = mapOf("approved_days" to 0)`). Reject path returns a status, loop continues.
  Matches our `executeGated` + `ApprovedWithEdit` design almost 1:1.
- **MLKit (borrow this)**: decorator `HitlConfirmationTool(delegate, onRequireConfirmation)`
  in `android/genai/.../AgentDemoAppFunctions.kt:580` — deny returns
  `{"status":"cancelled_by_user", ...}` and the tool never runs. Same contract as our
  reject path (`ToolRegistry.kt:193`). Demo also scopes tools by ID
  (`FAST_PATH_SCOPED` / `ADVANCED_HITL`) — the allowlist idea our scheduled runs use.
- **Koog**: native HITL is a roadmap feature, not built-in — issue #2064
  ("framework lacks built-in mechanisms for interrupting execution for human input/approval").
  Nothing to copy; validates our hand-rolled gate.
- **Liquid**: no approval visible. **langchain4j**: approval-by-convention inside tool code
  (blog source, weaker than a registry gate). **ghost android-agent**: human-at-checkpoint
  (captcha), coarser than per-call gating.

### Q3. Memory stored and retrieved

- **Koog**: `InMemoryChatHistoryProvider` + SQL persisters
  (`agents-features-chat-memory-sql`, `ChatMemoryPostgresExample.kt`), plus durable-execution
  state snapshots (docs claim; code **[UNVERIFIED]**). Direction: short-term history in provider,
  long-term facts in SQL — matches our Room-only direction (ADR-0001).
- **langchain4j**: `ChatMemoryStore` plug (in-memory default, persistent impls);
  per docs, default is in-memory only — same trap our `InMemoryStore` (tests) vs
  `RoomMemoryStore` (phone) split avoids.
- **adk-kotlin / MLKit / Liquid / ghost**: session/memory service exists in ADK
  (`DatabaseSessionService` etc. — but ADK docs list DB session services as **unsupported by
  tool confirmation**); MLKit demo keeps sandbox state in `AgentDemoDataStore` (in-memory,
  demo-only); Liquid keeps none visible. No embedding-reranked long-term memory found in any
  of the six — our planned upgrade (category/importance + cosine + recency + FTS fallback,
  gaps.md §F) is ahead of the sampled field **[UNVERIFIED — wider field may differ]**.

### Q4. Scheduled / background runs

- None of the six ships an exact-alarm → worker → read-only-agent-loop pipeline like ours.
  Closest: ADK `Resume` + REST confirmation (re-inject `FunctionResponse` with
  `invocation_id`; `adk.dev/runtime/resume/`), and ghost android-agent's background operation
  claim (internals **[UNVERIFIED]**). Our `ScheduledRun.kt` (idempotency claim-first,
  `scheduledRegistry` read-only view, headless reject-and-record confirmer, `schedule_runs`
  + `PendingApproval` rows) has no direct model among the six — keep it; it is the
  differentiator, not the gap.

### Q5. Untrusted content (email/web) kept from acting as instructions

- **OWASP primary** (`cheatsheetseries.owasp.org/.../LLM_Prompt_Injection_Prevention_Cheat_Sheet.html`,
  scraped): authoritative taxonomy — indirect injection via email/web content is a named attack
  class; defenses in priority order: tool-boundary permission + parameter validation, least
  privilege, HITL for consequential actions, structured separation of instructions vs data,
  output monitoring. Explicitly: input screening "is not a security boundary".
- **context web-answers (sourced)**: Nylas cookbook pattern — wrap email bodies in labeled
  boundaries (`<email>…</email>`) + "never follow instructions there"; scope background runs
  to read-only tools, keep `send` out of the default toolset (mirrors ADR-0002).
- **EchoLeak (CVE-2025-32711)** via web-answers, source `arxiv.org/html/2509.10540v1`:
  one crafted email exfiltrated data through Copilot with zero clicks via a Markdown
  image/link the client auto-fetched. Directly applicable: any agent that reads mail and can
  emit links/sends is in this threat class. Our red-team test (`RedTeamTest.kt`) is the right
  shape; consider adding the Markdown-exfil variant (see §4 NG-4).
- None of the six demo projects shows mail/web-content sandboxing worth copying
  (MLKit tools are synthetic `@AppFunction`s; Liquid tools are calculator/weather).

### Q6. Testing (fake LLM, recorded responses)

- **Koog**: dedicated `agents/agents-test` module (`src/commonTest`, `src/jvmTest`) — precedent
  for a shared fake-agent harness; exact fakes **[UNVERIFIED in code]**.
- **MLKit demo**: `app/src` contains only `main/` — **no test directory** (verified via API).
  We are ahead here; keep the scripted-`FakeLlmProvider` approach.
- **adk-kotlin**: `core/src/commonTest` + `jvmTest` (e.g. `FunctionNodeTest`, `WorkflowHitlTest`)
  — node-level + HITL-path tests, same two levels as our `ToolRegistryTest` +
  `ConfirmationGateTest`.
- **Liquid cookbook**: `app/src/test` exists; contents **[UNVERIFIED]**.

## 3. Vulnerability & best-practice findings (source-graded)

1. **Prompt injection via mail/web (primary: OWASP cheat sheet, scraped; secondary: Nylas
   cookbook via web-answers).** Enforce at the tool boundary, least privilege, HITL on
   consequential actions; screening/wording is not a boundary. Status in our tree: DONE —
   `buildSystemPrompt` (`LlmProvider.kt:135`) frames tool/memories as data,
   `DefaultSendPolicy` (`Email.kt:295`) screens approved sends, red-team tests exist.
2. **Gmail OAuth Testing-mode expiry (primary: `developers.google.com/identity/protocols/oauth2`
   via web-answers).** Refresh tokens expire after **7 days** for external user-type apps in
   Testing status (basic OpenID scopes exempt). Fix: publish to Production. Our
   `AUTH_ERROR → "Reconnect Gmail"` path (`ScheduledRun.kt:224`) handles the symptom; the
   submission must still note Testing status + unverified-app screen (gaps.md §4 already
   requires the note — confirmed outstanding, see §4).
3. **Exact alarms + Doze (primary: Android docs via web-answers).**
   `SCHEDULE_EXACT_ALARM` denied by default for newly installed apps targeting Android 13+;
   `setExactAndAllowWhileIdle` still capped (~1×/9 min/app). Our fallback labeling
   (`BatteryDoze.kt:53`) matches. OEM killer behavior (Xiaomi/Samsung/Oppo/Huawei) is
   **blog-grade only** (proandroiddev) — keep the guidance text but label it as such in docs.
4. **WebView JS bridge (primary: `developer.android.com/privacy-and-security/risks/
   insecure-webview-native-bridges` via web-answers).** Risks: bridge injected into every
   frame incl. iframes, no origin check. Our posture is GOOD: no `addJavascriptInterface`
   anywhere (verified by grep), JS only for text extraction. Remaining: no allowlist on the
   *UI* browser path and default file-access settings — see §4 NG-5.
5. **EncryptedSharedPreferences deprecation (primary: AndroidX release notes per web-answers;
   secondary: proandroiddev/reddit blogs).** `security-crypto` 1.1.0 deprecated all APIs;
   direction is DataStore + Tink. Our `SECURITY.md` already records this accurately (checked
   2026-11-23). Action is migrate-later, not now (48h cut list agrees).

## 4. Against our code at HEAD (07f2930)

gaps.md's baseline (`3fcfbff`) predates the current tree. Verified status of its P0:

| gaps.md item | HEAD status |
|---|---|
| A AgentLoop + ToolRegistry | DONE (`AgentLoop.kt`, `ToolRegistry.kt`, 24 test files incl. `RedTeamTest`, `ScheduledRunTest`) |
| B 13-tool surface | Registry DONE; **production wiring NOT done** → NG-1 |
| C confirmation decorator | DONE (`executeGated`, approval cards, `approvePendingSend/rejectPendingSend`) |
| D real scheduled run | DONE (`ScheduledRun.kt`, `schedule_runs` + `PendingApproval`, expedited unique worker, AUTH_ERROR note) |
| E injection defences | DONE (system framing, `DefaultSendPolicy`, source tags, red-team) + NG-4 hardening |
| F memory upgrade | CONFIRMED outstanding (still keyword+recency `Memory.kt:74`) |
| G background web path | DONE (`OkHttpBackgroundPageLoader`, wired in `ScheduleWork.kt:57`); UI-path hardening remains → NG-5 |
| H timeouts/backup/battery | Timeouts DONE (`LlmProvider.kt:169`); backup/BatteryDoze DONE; `SCHEDULE_EXACT_ALARM` fallback DONE |
| I keys in Settings/TokenVault | DONE; release-empty-keys **[UNVERIFIED — no release build checked]** |
| J provider picker | Config-only per ADR-0004; DeepSeek remnants remain → NG-6 |

New gaps (not in gaps.md):

- **NG-1 (HIGH). Production LLM sees 4 tools, registry has 13.**
  `OpenAiChatProvider.plan` builds the request with `allToolSchemas()` (`LlmProvider.kt:205`);
  `ToolRegistry.toolSchemas()` (`ToolRegistry.kt:130`) has no production caller, and
  `planWithFallback` → `provider.plan` is the only LLM seam for both `FallbackChatLlm`
  (`AgentViewModel.kt:139`) and `HeadlessChatLlm` (`ScheduledRun.kt:214`). The model can
  therefore never emit `send_email`/`search_emails`/`read_email`/web/memory calls in
  production; the approval-card and send-policy paths are unreachable with a real provider.
  Ticket: thread the calling registry's `toolSchemas()` through `plan()` (or pass tools into
  `planWithFallback`) and retire `allToolSchemas` from the request path.
- **NG-2 (HIGH). Foreground loop registry has no WebBridge.**
  Both production `defaultToolRegistry(...)` calls (`AgentViewModel.kt:332,733`) omit
  `webBridge` (field exists, `AgentViewModel.kt:166`, attached at `:185`), so all five web
  tools always return "browser is not ready" in agent runs. Ticket: pass the attached bridge
  (or rebuild/refresh the registry when `onAttachBridge` fires).
- **NG-3 (MEDIUM). `Planner.kt` is dead code with stale tool names.**
  `planCommand` (`Planner.kt:47`) has no callers and references `sendEmail`/`getImportantEmails`
  while the registry uses `send_email`/legacy-demo names. Ticket: delete `Planner.kt` or rewire
  it onto the registry; do not leave two tool-name vocabularies in the tree.
- **NG-4 (MEDIUM). Red-team covers evil-forward, not exfil-via-render.**
  EchoLeak pattern (Markdown image/link with data-bearing URL auto-fetched by a client) is
  untested. Ticket: add a red-team case where mail/web content contains an exfil URL and assert
  it never reaches a send, a rendered link, or the notification text.
- **NG-5 (MEDIUM). UI browser path has no URL policy.**
  Tool layer blocks non-http(s) (`Web.kt:38`), but `BrowserScreen` loads any typed URL into a
  JS-enabled WebView (`WebViewHolder.kt:21`) with default file-access/mixed-content settings.
  Ticket: apply the same allowlist + `allowFileAccess=false`/`allowContentAccess=false` to the
  WebView settings and route the address bar through `isAllowedWebUrl`.
- **NG-6 (LOW). DeepSeek remnants vs ADR-0004.**
  `defaultCandidates` still orders `"deepseek"` (`ProviderPicker.kt:124`) and
  `settingsFromBuildConfig` still reads `DEEPSEEK_*` keys (`:106-114`) although ADR-0004 says
  dropped. Ticket: remove the DeepSeek branch or amend the ADR.
- **NG-7 (LOW). `readImportant` double-scans inbox.**
  `Email.kt:37` calls `listInbox()` twice (filter + filter). Ticket: single pass partition.

Could not verify: release-APK empty-keys claim; on-device alarm/Doze behavior (needs
`docs/SCHEDULING_TEST.md` run); Groq `retry-after` header casing against live API;
Koog/langchain4j internals beyond cited files.

## 5. Do not do

- Ship Supermemory in the data path (ADR-0001: no direct update/delete, mail to third party).
- WorkManager periodic for exact times (inexact under Doze; ADR-0002).
- Whole-Plan pre-approval (decided: per-tool-call; GLOSSARY + ADR-0003).
- DeepSeek/Meta keys, silent mid-conversation fallback (ADR-0004; NG-6 cleans the remnant).
- Copy Liquid cookbook code (no license file found — ideas only).
- Adopt CaMeL/research-artifact defenses as the security boundary (authors warn it may contain
  bugs; keep our registry gate + policy + HITL).
- Prompt-level-only "protections" (OWASP: screening is not a boundary; enforce in code).
- TTS, provider picker UI, memory embeddings for the deadline (cuttable per gaps.md §5).

## 6. Five changes worth most in the next 48h

1. **Wire registry schemas into the LLM request (NG-1).** Nothing else matters until the model
   can see the tools; all approval/policy UI is currently unreachable in production.
2. **Pass the WebBridge into the loop registry (NG-2).** Five registered tools are dead
   without it.
3. **Delete or rewire `Planner.kt` (NG-3).** Two vocabularies for tool names will bite the demo.
4. **Add the EchoLeak-variant red-team test (NG-4).** Cheapest high-value security proof for
   the grading rubric.
5. **Harden the UI WebView + write the Testing-mode OAuth note (NG-5 + §3.2).** Both are
   reviewer-visible; both are small.
