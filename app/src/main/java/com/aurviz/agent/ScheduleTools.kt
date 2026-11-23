package com.aurviz.agent

// Ticket 73 (part of #71): Schedule Tool adapter. Owns the three Schedule
// Tool declarations — gated create, ungated list, gated cancel with unknown
// id guidance — and explicitly registers them into the small validate-and
// gate kernel (ToolRegistry). No behavior change: create and cancel stay
// MEDIUM risk with per-call Confirmation, list stays LOW risk ungated, a
// missing store still returns a non-retryable Failure, and the kernel
// still refuses gated Tools on the ungated path.
fun createScheduleTool(scheduleStore: ScheduleStore?): ToolDefinition {
    val scheduleFn = scheduleToolSchema().getJSONObject("function")
    return ToolDefinition(
        name = scheduleFn.getString("name"),
        description = scheduleFn.getString("description"),
        parameters = scheduleFn.getJSONObject("parameters"),
        // State-changing (arms alarms); ADR-0003 requires per-call Confirmation.
        riskLevel = RiskLevel.MEDIUM,
        requiresConfirmation = true,
        resultType = "schedule",
        execute = { args ->
            val store = scheduleStore
                ?: return@ToolDefinition ToolResult.Failure("schedule store is not available", retryable = false)
            val task = args["task"] as String
            val whenText = args["when"] as String
            val parsed = parseSchedule("$task $whenText")
                ?: return@ToolDefinition ToolResult.Failure(
                    "could not understand a time in 'when': '$whenText'",
                    retryable = true,
                )
            val saved = createSchedule(store, task, parsed.hour, parsed.minute, parsed.days)
            ToolResult.Success(
                mapOf(
                    "id" to saved.id,
                    "instruction" to saved.instruction,
                    "hour" to saved.hour,
                    "minute" to saved.minute,
                ),
            )
        },
    )
}

fun listSchedulesTool(scheduleStore: ScheduleStore?): ToolDefinition {
    val listFn = listSchedulesToolSchema().getJSONObject("function")
    return ToolDefinition(
        name = listFn.getString("name"),
        description = listFn.getString("description"),
        parameters = listFn.getJSONObject("parameters"),
        // Read-only; ADR-0003 gates only sensitive calls.
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false,
        resultType = "schedules",
        execute = { _ ->
            val store = scheduleStore
                ?: return@ToolDefinition ToolResult.Failure("schedule store is not available", retryable = false)
            ToolResult.Success(
                listSchedules(store).map { schedule ->
                    mapOf(
                        "id" to schedule.id,
                        "instruction" to schedule.instruction,
                        "hour" to schedule.hour,
                        "minute" to schedule.minute,
                        "days" to schedule.days?.toList(),
                    )
                },
            )
        },
    )
}

fun cancelScheduleTool(scheduleStore: ScheduleStore?): ToolDefinition {
    val cancelFn = cancelScheduleToolSchema().getJSONObject("function")
    return ToolDefinition(
        name = cancelFn.getString("name"),
        description = cancelFn.getString("description"),
        parameters = cancelFn.getJSONObject("parameters"),
        // State-changing (disarms alarms); ADR-0003 requires per-call Confirmation.
        riskLevel = RiskLevel.MEDIUM,
        requiresConfirmation = true,
        resultType = "schedule",
        execute = { args ->
            val store = scheduleStore
                ?: return@ToolDefinition ToolResult.Failure("schedule store is not available", retryable = false)
            val id = args["schedule_id"] as String
            val existing = listSchedules(store).find { it.id == id }
                ?: return@ToolDefinition ToolResult.Failure(
                    "unknown schedule id: '$id'. Use list_schedules to see saved routines.",
                    retryable = true,
                )
            deleteSchedule(store, existing.id)
            ToolResult.Success(mapOf("id" to existing.id, "deleted" to true))
        },
    )
}

/** The Schedule adapter: all Schedule Tool declarations in registration
 *  order (create, list, cancel). The default registry builder registers
 *  every entry into the kernel; scheduled-run tests register the same
 *  entries into a standalone kernel for the read-only view. */
fun scheduleTools(scheduleStore: ScheduleStore?): List<ToolDefinition> =
    listOf(
        createScheduleTool(scheduleStore),
        listSchedulesTool(scheduleStore),
        cancelScheduleTool(scheduleStore),
    )
