package com.aurviz.agent

// Ticket #87: pure Command-screen presentation helpers (JVM-tested in
// CommandPlanUiTest). The Compose layer renders these; it adds no
// decisions of its own. Copy follows GLOSSARY.md: Agent, Plan, Tool,
// Session, Confirmation, Preference — never raw provider ids, raw
// snake_case Tool names, or banned words.

// Human provider names for the Command header ModelPill and the provider
// sheet. Null (system default) and unknown ids never leak raw text.
fun providerDisplayName(providerId: String?): String =
    when (providerId?.lowercase()) {
        null -> "Auto"
        "gemini" -> "Gemini"
        "deepseek" -> "DeepSeek"
        "meta" -> "Meta"
        else -> toolLabel(providerId)
    }

// Session counter line for the Command header (no "thread"/"chat" copy).
fun sessionTurnsLine(turns: Int): String =
    if (turns == 1) "Session · 1 turn" else "Session · $turns turns"

// One-line "k: v, k: v" argument summary for an expandable Plan step.
// Null when the call took no arguments.
fun argsSummary(arguments: Map<String, Any?>): String? {
    if (arguments.isEmpty()) return null
    return arguments.entries.joinToString(", ") { (key, value) -> "$key: $value" }
}

// Confirmation card title/detail (GLOSSARY Confirmation). The generic path
// maps through toolLabel so no raw snake_case Tool id reaches the screen.
// AgentViewModel.confirmationTextFor delegates here.
fun confirmationTitleFor(call: ToolCall, args: Map<String, Any?> = call.arguments): String =
    if (call.name == "send_email") {
        "Send email to ${(args["to"] as? String).orEmpty()}"
    } else {
        "Run ${toolLabel(call.name)}"
    }

fun confirmationDetailFor(call: ToolCall, args: Map<String, Any?> = call.arguments): String =
    if (call.name == "send_email") {
        val to = (args["to"] as? String).orEmpty()
        val subject = (args["subject"] as? String).orEmpty()
        val body = (args["body"] as? String).orEmpty()
        "To: $to\nSubject: $subject\n$body"
    } else {
        args.entries.joinToString("\n") { (key, value) -> "$key: $value" }
    }

// The single error row for a Plan run: the FAILED Tool row when present
// (never the duplicated trailing "Error:" tail), else the first FAILED
// row, else null. The screen renders at most one StatusBanner from this.
fun planFailure(steps: List<TimelineStep>): TimelineStep? =
    steps.firstOrNull { it.state == StepState.FAILED && it.title.startsWith("Tool ") }
        ?: steps.firstOrNull { it.state == StepState.FAILED }

// Rows the Plan step list renders: the raw "Error:" FAILED tail is folded
// into the single planFailure banner, never rendered as a second red row.
fun visiblePlanSteps(steps: List<TimelineStep>): List<TimelineStep> =
    steps.filterNot { it.state == StepState.FAILED && it.title.startsWith("Error:") }

private val ToolTitlePrefix = Regex("^Tool \\d+ of \\d+: ")

// Display title for a Plan step row: the "Tool i of n" prefix is kept and
// the registry name behind it maps through toolLabel. Non-tool rows pass
// through unchanged.
fun stepDisplayTitle(step: TimelineStep): String {
    val tool = step.toolName ?: return step.title
    val prefix = ToolTitlePrefix.find(step.title)?.value ?: return step.title
    return prefix + toolLabel(tool)
}
