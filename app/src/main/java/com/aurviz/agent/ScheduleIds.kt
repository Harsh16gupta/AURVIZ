package com.aurviz.agent

// Schedule archipelago collapse (ticket 1): one pid codec for the two
// Schedule-owned prefix adapters. Room adapters delegate here; stored
// pids are unchanged so no DB migration. Memory's room_ prefix stays
// with Memory; schedule_runs.pid stays numeric.
object ScheduleIds {
    private const val SCHEDULE_PREFIX = "sched_"
    private const val APPROVAL_PREFIX = "approval_"

    fun scheduleId(pid: Long): String = "$SCHEDULE_PREFIX$pid"

    fun schedulePid(id: String): Long? = pidFor(id, SCHEDULE_PREFIX)

    fun approvalId(pid: Long): String = "$APPROVAL_PREFIX$pid"

    fun approvalPid(id: String): Long? = pidFor(id, APPROVAL_PREFIX)

    private fun pidFor(id: String, prefix: String): Long? =
        if (id.startsWith(prefix)) id.removePrefix(prefix).toLongOrNull() else null
}
