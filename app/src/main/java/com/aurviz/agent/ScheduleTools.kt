package com.aurviz.agent

import org.json.JSONArray
import org.json.JSONObject

// Ticket 73 (part of #71): Schedule Tool adapter. Owns the three Schedule
// Tool declarations — gated create, ungated list, gated cancel with unknown
// id guidance — and explicitly registers them into the small validate-and
// gate kernel (ToolRegistry). No behavior change: create and cancel stay
// MEDIUM risk with per-call Confirmation, list stays LOW risk ungated, a
// missing store still returns a non-retryable Failure, and the kernel
// still refuses gated Tools on the ungated path.
// Ticket 75 (part of #71): the schema bodies live here in the owning
// adapter — no shared hardcoded schema list; the kernel's toolSchemas()
// view is the single advertised Tool list.
fun createScheduleTool(scheduleStore: ScheduleStore?): ToolDefinition {
    return ToolDefinition(
        name = "create_schedule",
        description = "Save a recurring routine that runs later. Split the request into WHAT to do (task) and WHEN it runs (when, in the user's own words).",
        parameters =
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("task", JSONObject().put("type", "string").put("description", "What the routine does, e.g. read emails"))
                        .put("when", JSONObject().put("type", "string").put("description", "When it runs, in the user's own words, e.g. at 2:38 a.m.")),
                )
                .put("required", JSONArray().put("task").put("when")),
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
    return ToolDefinition(
        name = "list_schedules",
        description = "List the saved recurring routines with their ids, instructions, and times.",
        parameters =
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()),
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
    return ToolDefinition(
        name = "cancel_schedule",
        description = "Delete a saved recurring routine. Use the schedule_id from list_schedules.",
        parameters =
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("schedule_id", JSONObject().put("type", "string").put("description", "The routine id, e.g. sched_1")),
                )
                .put("required", JSONArray().put("schedule_id")),
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
