# Browser URL policy + page-host settings — human-pass record (ticket 41/NG-5)

Date: 2026-11-23. Worktree `ticket/41-browser-url-policy`.
Recorder: ticket-41 agent, same headless container as ticket 37 (Android SDK present, **no device attached**).

## What ran here (verifiable JVM subset, green)

- `./gradlew :app:testDebugUnitTest` → **299 tests, 0 failures, 0 errors, 0 skipped** (28 classes; +4 in the new `BrowserPolicyTest` over the 295-test baseline).
- New coverage: `BrowserPolicyTest` — web addresses load unchanged, non-web addresses refuse with an http-explaining reason, bare hosts are not auto-upgraded, routing verdicts match the Tool-layer allowlist.
- Prior art still green: `WebTest` (allowlist + blocked-carries-url), `WebToolsTest.allowlistBlocksFileAndNonHttpUrls`, `WebBackgroundTest`.

## What needs a human device pass (not run here)

WebView settings and typed-URL behavior cannot be verified on the JVM — they need a real device. Steps for the human pass:

### Settings audit (page host)

In `WebViewHolder.obtain` confirm, then spot-check on-device via remote WebView inspection:

1. `javaScriptEnabled = true` (unchanged — text-extraction-only `evaluateJavascript` in `WebViewBridge`; no `addJavascriptInterface` anywhere in `app/src`).
2. `allowFileAccess = false`, `allowContentAccess = false` — page content can never reach local files.
3. `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false` — file-URL variants pinned off.
4. `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW` — mixed-content posture pinned.

Guidance labeling (kept honest per ticket): items 1–4 are **platform-doc requirements** — they follow the `android.webkit.WebSettings` / WebView platform documentation for the app's `minSdk 26 / targetSdk 35` toolchain. Any claim that a specific handset blocks or renders mixed content differently is **OEM-specific behavior** (blog-grade, device-dependent) and must be recorded with device model + Android version + OEM, not stated as a platform guarantee.

### Typed-URL matrix (Browser tab address bar)

Type each into the Browser tab and press Open; record the visible `webError`/status line:

| Typed | Expected |
|---|---|
| `https://example.com` | Loads as before (happy path unchanged). |
| `http://example.com/path?q=1` | Loads as before. |
| `file:///etc/passwd` | Refuses with explanation mentioning http/https; nothing loads. |
| `javascript:alert(1)` | Refuses with explanation; nothing loads. |
| `example.com` (bare host, no scheme) | Refuses with explanation (no silent `https://` upgrade); nothing loads. |

Also tap a non-web link from the Extracted panel (e.g. a `file:` href) — it routes through the same `openWeb` decision and must refuse the same way.

Result of the on-device settings audit + typed-URL matrix in *this* environment: **NOT RUN — no device attached.** The tables above are the method, not a result.
