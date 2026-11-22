# Aurviz Personal Agent

Android personal AI agent that understands voice and text commands and executes real-world tasks on the user's behalf.

## Language

**Agent**:
Orchestrator that interprets a command, plans steps, and invokes tools to complete them.
_Avoid_: Chatbot, assistant

**Plan**:
Ordered set of tool invocations the Agent creates to satisfy one command.
_Avoid_: Workflow, script

**Tool**:
Single real-world capability the Agent can invoke, such as sending email or opening a website.
_Avoid_: Plugin, action, function

**Memory**:
User-specific information remembered across app restarts and used to personalize future commands.
_Avoid_: History, cache, log

**Preference**:
A durable choice or habit of the user, such as preferred email tone or morning summary time.
_Avoid_: Setting, option

**Interaction**:
Record of one past command, its plan, and its outcome.
_Avoid_: Session, chat log

**Schedule**:
A recurring instruction with cron-like timing that triggers the Agent without a new command.
_Avoid_: Alarm, timer, cron job

**Confirmation**:
Explicit user approval required before a sensitive Tool runs.
_Avoid_: Consent popup, authorization

**Voice Command**:
Spoken input converted to text and handled like a typed command.
_Avoid_: Voice note, dictation

**Task Execution**:
The run of one Plan, including status updates and the final result shown to the user.
_Avoid_: Job run, background task
