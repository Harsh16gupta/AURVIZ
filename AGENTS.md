## Agent skills

### Issue tracker

Issues live in GitHub Issues for Harsh16gupta/AURVIZ (gh CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical labels used as-is: needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.

## Rules for agents

### Boundaries

- **UI** (Compose screens, preview cards, timeline): renders state, collects approvals. Never executes tools directly, never holds API keys.
- **Agent** (`AgentLoop`, `Planner`, `AgentViewModel`): orchestrates the loop, moves results into UI state. Holds no tool implementations.
- **Tools** (registry, schemas, executors): each tool declares JSON schema, risk level, `requiresConfirmation`, and result type. The confirmation gate lives in the registry - the model has no path around it. Tools never touch UI.
- **Memory** (Room via `MemoryStore` only): the sole persisted memory path. No cloud memory provider in the shipped path.
- **Scheduler** (exact alarm → expedited unique Worker → agent loop): receivers do no database work on raw threads; scheduled runs use the read-only tool allowlist; sends and schedule writes from a scheduled run become `PendingApproval` rows, never executions.

Use glossary terms exactly as defined in `GLOSSARY.md`. If output contradicts an ADR, flag it explicitly instead of silently overriding.

### No secrets in code

Keys live in `local.properties` / BuildConfig or user-entered Settings stored in `TokenVault`. Never commit keys, never embed them in source, APKs, or docs. Run a secret scan before the final push.

### Tests required per ticket

Every ticket ships with unit tests: `AgentLoop` with scripted fake LLM (multi-step, tool error then recovery, max-step abort, reject path), prompt-injection red-team test for any send-path change, cron/timezone edge cases for scheduler changes, memory ranking for retrieval changes. A ticket without tests is not done.

### Small commits

One ticket per commit, concise message. Do not bundle unrelated changes.

### Validation log

Whenever you catch your own or another agent's mistake - a wrong claim about the code, ADR/code drift, a missing test path - add an entry to `docs/VALIDATION_LOG.md` with file:line evidence before moving on.
