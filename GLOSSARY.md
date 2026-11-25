# Aurviz Personal Agent

Android personal AI agent that understands voice and text commands and executes real-world tasks on the user's behalf.

## Language

**Agent**:
Orchestrator that interprets a command, plans steps, and invokes tools to complete them.
_Avoid_: Chatbot, assistant

**Plan**:
The sequence of tool calls the agent loop actually made to satisfy one command, recorded with their results. The draft does not exist up front, so a Plan is never approved in advance - approval happens per sensitive tool call.
_Avoid_: Workflow, script

**Tool**:
Single real-world capability the Agent can invoke, such as sending email or opening a website.
_Avoid_: Plugin, action, function

**Memory**:
User-specific information stored in Room on-device and used to personalize future commands; optional opt-in Mem0 sync mirrors best-effort when enabled, Room stays source of truth for reads.
_Avoid_: History, cache, log

**Preference**:
A durable choice or habit of the user, such as preferred email tone or morning summary time.
_Avoid_: Setting, option

**Interaction**:
Record of one past command, its plan, and its outcome.
_Avoid_: chat log

**Session**:
Sequence of Interactions sharing one short-term transcript. Turns inside one Session resolve follow-ups against each other; a new Session starts with an empty transcript. Memory stays available to every Session.
_Avoid_: chat log

**Schedule**:
A recurring instruction with cron-like timing. At fire time an exact alarm enqueues an expedited unique Worker that runs the agent loop with the read-only tool allowlist - never the send tool.
_Avoid_: Alarm, timer, cron job

**Confirmation**:
Explicit user decision required before each sensitive tool call runs. The registry gate suspends the loop until the user confirms, edits, or rejects.
_Avoid_: Consent popup, authorization

**PendingApproval**:
A deferred Confirmation awaiting the in-app decision: an unattended run attempted a sensitive tool with no one present, so the attempt waits in the held list - nothing ran yet. Confirming runs it exactly once; denying drops it untouched.
_Avoid_: Approval queue, pending request

**Voice Command**:
Spoken input converted to text and handled like a typed command.
_Avoid_: Voice note, dictation

**Task Execution**:
The run of one Plan by the agent loop, including status updates and the final result shown to the user.
_Avoid_: Job run, background task
