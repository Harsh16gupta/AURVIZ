package com.aurviz.agent

import java.time.ZoneId
import java.time.ZonedDateTime

// Schedule archipelago collapse (ticket 3): one fire seam. Pure,
// JVM-testable; Android adapters supply runOnce + perform the arm.
// Receivers only enqueue work, never touch Room inline (ADR-0002).
enum class FireStatus { RAN, DUPLICATE_SKIPPED, SCHEDULE_MISSING, FAILED }

data class FireOutcome(
    val status: FireStatus,
    val nextArmMillis: Long?,
    val run: ScheduleRun? = null,
    val notification: ScheduleNotification? = null,
    val blocked: List<PendingApproval> = emptyList(),
)

fun fireSchedule(
    scheduleId: String,
    fireTime: Long,
    scheduleStore: ScheduleStore,
    zone: ZoneId = ZoneId.systemDefault(),
    now: ZonedDateTime = ZonedDateTime.now(zone),
    runOnce: (Schedule) -> ScheduledRunOutcome,
): FireOutcome {
    val schedule = scheduleStore.list().find { it.id == scheduleId }
        ?: return FireOutcome(FireStatus.SCHEDULE_MISSING, null)
    val nextArm = nextFireTime(schedule, zone, now)?.toInstant()?.toEpochMilli()
    return try {
        when (val outcome = runOnce(schedule)) {
            is ScheduledRunOutcome.DuplicateSkipped ->
                FireOutcome(FireStatus.DUPLICATE_SKIPPED, nextArm, outcome.run)
            is ScheduledRunOutcome.Ran ->
                FireOutcome(FireStatus.RAN, nextArm, outcome.run, outcome.notification, outcome.blocked)
        }
    } catch (_: Exception) {
        FireOutcome(FireStatus.FAILED, nextArm)
    }
}
