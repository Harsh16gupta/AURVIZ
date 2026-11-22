# Per-stage timing medians — 10 runs (ticket 37)

Date: 2026-11-23. Commit `f251cf2`. Source: `PerfTimingsTest`
(`app/src/test/java/com/aurviz/agent/PerfTimingsTest.kt`), run on the
submission machine via `./gradlew :app:testDebugUnitTest --tests
"com.aurviz.agent.PerfTimingsTest"` — JVM, no network, no device, 500
synthetic memories. First prompt sample includes one-off JIT/string-cache
warmup; medians are robust to it.

## Raw 10-run data (ms)

| Stage | 10 runs (ms) | Median |
|---|---|---|
| Memory recall `recallFor` over 500 rows (recallMs stage) | 2.011, 1.952, 2.006, 1.472, 1.228, 1.251, 1.313, 1.348, 1.277, 1.234 | **1.348** |
| System-prompt shaping `buildSystemPrompt(top5)` (planMs input stage) | 3.192, 0.040, 0.028, 0.032, 0.023, 0.026, 0.025, 0.027, 0.022, 0.022 | **0.027** |
| Registry validation + `search_memory` dispatch (toolMs stage) | 2.159, 1.065, 1.827, 0.878, 0.863, 0.865, 0.860, 0.868, 0.876, 0.881 | **0.878** |

Bounds asserted in the test (regression tripwires, not device SLOs):
recall median < 250 ms, prompt median < 50 ms, registry median < 250 ms.

## Before/after optimization: top-k trimming of the memory prompt section

The shipped path passes `max_results` (default 5) so only the top hits are
rendered into the `[memories]` system section; rendering all 500 rows is the
"before".

| Variant | 10 runs (ms) | Median | Prompt section size |
|---|---|---|---|
| Before: `renderMemorySection(all 500)` | 0.592, 0.361, 0.266, 0.145, 0.141, 0.143, 0.149, 0.144, 0.143, 0.163 | **0.149** | ~39.8 KB chars |
| After: `renderMemorySection(top 5)` | 0.004, 0.003, 0.003, 0.003, 0.003, 0.003, 0.003, 0.003, 0.003, 0.002 | **0.003** | ~0.4 KB chars |

Effect: **~50× faster section render and ~100× smaller prompt section**
(~39.8 KB → ~0.4 KB), which is what keeps per-turn LLM latency and cost
flat as the memory table grows. The test also asserts the trimmed section
stays < 1 KB chars while the full section exceeds 10 KB chars.

## Profiler screenshot

**Not obtainable in this environment** (headless container, no device, no
Android Studio profiler session). No screenshot is included and none is
fabricated. Human-pass method: open Android Studio → Profile app on a
physical device → run one command end-to-end → capture the Memory profiler
timeline during `AgentLoop.run` + `recallFor` and attach the screenshot
here with device model and Android version.

## What still needs a device (not measured here)

STT latency, LLM first-token latency, and end-to-end command latency need
microphone, network keys, and a device, so they are **not reported**.
Method for the human pass: Settings shows live `planMs` / `toolMs` /
`recallMs` per run (`SettingsScreen` footer + status line); run the same
voice command 10 times on-device, take medians, append them to this file.
