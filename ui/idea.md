Because the assignment emphasizes an **action-taking agent** rather than a chatbot, the UI should be structured as an **Operational Command Center**—prioritizing live execution traces, human-in-the-loop approvals, and structured state over plain chat bubbles.

---

## 1. App Shell & Design Direction

* **Visual Style:** Light-native, Swiss-technical minimalism. High-contrast `#FFFFFF` canvas, crisp `1px` borders, `#0300EB` (cobalt) for active agent states and primary actions, and monospace typography for timestamps, cron expressions, latency metrics, and tool logs. No decorative shadows or cluttered dark cards.
* **Layout (Responsive Web App):**
* **Desktop:** Fixed left sidebar navigation (`240px`) + fluid main workspace + collapsible right-hand **Inspector Drawer** (for live browser views or tool logs).
* **Mobile Web / PWA:** Top status bar (agent online/offline + active task indicator) + main scrollable canvas + bottom navigation bar (4 tabs) with a floating **Voice Mic** button anchored above it.



---

## 2. Page-by-Page Breakdown

### Page 1: Command Center (Home)

*The primary workspace for issuing voice/text commands and watching the agent execute multi-step workflows.*

* **Header Bar:** Agent status pill (`Idle`, `Listening`, `Executing`), active model indicator (e.g., `Gemini 2.5 Flash`), and a quick toggle for "Require Confirmation for Sensitive Actions."
* **Execution Feed (Main Area):**
* Instead of plain chat bubbles, each user command creates a **Task Card** containing:
1. **Intent Header:** Original voice/text prompt + timestamp.
2. **Retrieved Memory Chips:** Small inline tags showing which personal memories were pulled for context (e.g., `Memory: Prefers concise bullet summaries`).
3. **Step-by-Step Execution Timeline:** Expandable rows for each tool call (`Gmail: Fetch Unread` → `LLM: Filter Important` → `Draft Summary`) with status icons (`Running`, `Done`, `Needs Approval`, `Failed`) and execution time in ms.
4. **Final Output Block:** Clean summary, extracted table, or action receipt.




* **Persistent Input Dock (Bottom):**
* Text input field + large **Push-to-Talk / Continuous Voice** button.
* Quick-action starter chips (e.g., *"Check important emails"*, *"Schedule daily 9 AM digest"*, *"Extract prices from URL"*).



---

### Page 2: Web Automation & Task Inspector

*Dedicated view (or expandable split-pane) for the "controlled browser interface" and multi-step debugging.*

* **Split Layout:**
* **Left Panel (Action Log):** Sequential list of browser actions (`Navigate to URL`, `Scroll 600px`, `Click Selector`, `Extract DOM Text`) with exact timestamps and status.
* **Right Panel (Controlled Browser Viewport):**
* Mock browser chrome showing the current URL, loading bar, and manual override buttons (`Pause Agent`, `Take Control`, `Abort`).
* Live rendered frame or step-by-step screenshot snapshots highlighting the element the agent is interacting with.




* **Extracted Data Tray (Bottom):** Structured JSON/Table preview of the data scraped from the page, with a one-click copy or "Save to Memory" button.

---

### Page 3: Scheduled Tasks (Automations)

*Manages cron-like recurring instructions and background execution history.*

* **Top Action Bar:** Natural-language schedule input (e.g., *"Every morning at 9 AM, check my important emails and prepare a summary"*) that parses into a readable schedule preview + `+ New Schedule` button.
* **Active Schedules List:** Clean table/card rows displaying:
* **Task Name & Prompt**
* **Schedule Badge:** Human-readable + cron string (e.g., `Daily at 09:00` · `0 9 * * *`)
* **Workflow Chain:** Mini pill sequence (`Email` → `Summarize` → `Notify`)
* **Next Run & Last Run Status:** (`Success · 2.4s` or `Failed · Retry`)
* **Controls:** Enable/Disable toggle switch, `Run Now` button, and `Delete` icon.


* **Run History Drawer:** Clicking any scheduled task opens its past execution logs and generated reports.

---

### Page 4: Personal Memory Bank

*Provides full transparency and control over the persistent memory layer.*

* **Search & Filter Bar:** Search input + category filter tabs (`All`, `Preferences`, `Facts`, `Past Interactions`).
* **Memory Table / List:** Each saved memory row shows:
* **Memory Statement** (e.g., *"User's primary email is harsh@example.com"*, *"Prefers formal tone for client emails"*).
* **Category Tag** & **Source** (e.g., `Extracted from Task #14` or `Added Manually`).
* **Usage Metadata:** Date created + number of times retrieved.
* **Actions:** Edit inline or one-click `Delete` (with undo toast).


* **Add Memory Button:** Manual modal to add a preference or fact directly.

---

### Page 5: Connections, Security & Telemetry (Settings)

*Covers OAuth, API configuration, permissions, and the assignment's performance metrics.*

* **Connected Accounts Section:**
* **Email (Gmail / OAuth):** Connection status badge, connected address, scopes granted, and `Reconnect` / `Disconnect` button.
* **AI Provider (Gemini API):** Connection health check, rate-limit/free-tier indicator, and masked API key status.


* **Safety & Permissions Controls:**
* Toggle matrix for **Explicit Confirmation Rules** (e.g., `Send Email: Always Ask`, `Web Form Submit: Always Ask`, `Read Emails: Auto-Approve`).


* **Performance & Latency Monitor (Bonus for Assignment Eval):**
* Compact metric cards showing **Avg. Agent Response Time**, **Avg. Tool Execution Latency**, and **Memory Retrieval Overhead**.
* Simple table of recent tasks with latency breakdowns so you can screenshot it directly for your Performance Analysis doc.



---

## 3. Critical Cross-App Modals & States

These 3 overlays are essential to prove the agent handles real-world constraints:

| Component | Trigger | Key UI Elements |
| --- | --- | --- |
| **Voice Listening Overlay** | Tapping the Mic button | Live audio waveform, real-time speech-to-text transcript, `Cancel` and `Send` buttons. |
| **Sensitive Action Confirmation Card** | Before sending an email or submitting a form | Highlighted inline card or modal showing **To**, **Subject**, and **Body** (editable), with `Approve & Send` and `Reject` buttons. |
| **Error / Offline Banner** | Network drop, API rate limit, or OAuth expiry | Non-blocking top banner with clear error code, reason, and a `Retry` or `Re-authenticate` action. |

---

## 4. How We Will Execute This

Once you approve this structure, we can proceed in three stages:

1. **Wireframe / Screen-by-Screen Visuals:** Generate clean, consistent UI mockups for each of the 5 pages (starting with the **Command Center** and **Confirmation Card** to lock in the visual language).
2. **Component & State Pass:** Refine the **Web Automation Viewport** and **Voice Overlay** states.
3. **Frontend Implementation (Optional):** Convert the approved designs into responsive React/Tailwind or HTML/CSS code ready to wire up to your backend/agent API.

Does this 5-page architecture cover everything you want, or would you like to adjust the layout (for example, mobile-first screens vs. desktop web dashboard) before we start designing Page 1?