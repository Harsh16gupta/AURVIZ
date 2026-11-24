# T1 tool-loop spike (issue #9)

One OpenAI-shape HTTP client (`t1_llm_provider.py`) covering Gemini /
DeepSeek / Meta — only base URL + key + model change (ADR-0004).

## What was verified (2026-11-22, TDD red→green, 6 tests)

- Request builder: POST `{base}/chat/completions`, `Bearer` key, `tools` +
  `tool_choice: auto` (slice 1).
- Response parser: `tool_calls` → `ToolCall(id, name, args)`; text-only
  replies yield `[]` + content (slice 2).
- Error map: 429 → `rate_limited` + `retry_after` + fallback; 401 → `auth`,
  no retry (slice 3).
- `FakeLlmProvider.plan()` returns canned `getImportantEmails(max_results=5)`
  for planner tests.
- LIVE: Gemini compat endpoint returned
  `tool_calls=[getImportantEmails(max_results=5)]`.

## Run

- `python3 -m unittest test_t1_llm_provider -v` (stdlib only, 6 tests)
- `python3 t1_live_check.py` (needs keys in `local.properties`)

## Android port

The Kotlin implementation lives in `app/` (`LlmProvider.kt`, JUnit tests in
`app/src/test`). It compiles with the project's Gradle wrapper:
`./gradlew :app:testDebugUnitTest` (needs Java 17 + Android SDK with
`sdk.dir` in `local.properties`). Verified 2026-11-22: 6/6 unit tests green,
zero warnings, plus the live Gemini `tool_calls` round-trip above.
