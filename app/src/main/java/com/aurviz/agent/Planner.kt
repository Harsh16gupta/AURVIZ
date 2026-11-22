package com.aurviz.agent

// T3: planner turns a typed command into a previewable Plan.
// Pure decision logic, JVM-testable; tested with stub LlmProvider.

/** Ordered set of Tool invocations that satisfy one command. */
data class Plan(val steps: List<ToolCall>)

sealed interface PlannerOutcome {
    data class Ready(val plan: Plan) : PlannerOutcome

    data class NeedsClarification(val question: String) : PlannerOutcome
}

// Required arguments per Tool. A slot counts as missing when it is
// absent, null, or blank text — the planner asks instead of guessing.
private fun requiredSlots(toolName: String): List<String> =
    when (toolName) {
        "sendEmail" -> listOf("to", "body")
        "getImportantEmails" -> listOf("max_results")
        "create_schedule" -> listOf("task", "when")
        "cancel_schedule" -> listOf("schedule_id")
        else -> emptyList()
    }

fun missingSlots(call: ToolCall): List<String> =
    requiredSlots(call.name).filter { slot ->
        val value = call.arguments[slot]
        when (value) {
            null -> true
            is String -> value.isBlank()
            else -> false
        }
    }

private fun slotQuestion(toolName: String, slot: String): String =
    when (toolName to slot) {
        "sendEmail" to "to" -> "Who should I send it to?"
        "sendEmail" to "body" -> "What should the message say?"
        "getImportantEmails" to "max_results" -> "How many emails should I check?"
        "create_schedule" to "task" -> "What should the routine do?"
        "create_schedule" to "when" -> "When should it run?"
        "cancel_schedule" to "schedule_id" -> "Which routine should I delete?"
        else -> "What's the $slot?"
    }

fun planCommand(command: String, provider: LlmProvider): PlannerOutcome {
    if (command.isBlank()) {
        return PlannerOutcome.NeedsClarification("What would you like me to do?")
    }
    val result = provider.plan(command)
    if (result.toolCalls.isEmpty()) {
        return PlannerOutcome.NeedsClarification(result.content ?: "Could you say that another way?")
    }
    for (call in result.toolCalls) {
        val missing = missingSlots(call)
        if (missing.isNotEmpty()) {
            return PlannerOutcome.NeedsClarification(slotQuestion(call.name, missing[0]))
        }
    }
    return PlannerOutcome.Ready(Plan(result.toolCalls))
}
