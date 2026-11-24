package com.aurviz.agent

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Session 3 Scheduling: parse-preview + CRUD + firing decision + result note.
// Pure decision logic, JVM-testable. Room (ScheduleRepository), WorkManager,
// AlarmManager, and notifications plug in later through ScheduleStore;
// the unit tests use InMemoryScheduleStore + FakeClock instead.

// One routine: what to run + when. days = null means every day;
// otherwise 1=Monday..7=Sunday (java.time.DayOfWeek numbers).
data class Schedule(
    val id: String,
    val instruction: String,
    val hour: Int,
    val minute: Int,
    val days: Set<Int>? = null,
)

// What the parser understood: when + what to do.
data class ParsedSchedule(
    val hour: Int,
    val minute: Int,
    val days: Set<Int>? = null,
    val instruction: String,
)

// A clock the tests control. Real wiring passes the current time in.
data class FakeClock(val hour: Int, val minute: Int, val dayOfWeek: Int)

// The plug shape. Tests use InMemoryScheduleStore; Room implements this.
interface ScheduleStore {
    fun save(instruction: String, hour: Int, minute: Int, days: Set<Int>? = null): Schedule
    fun list(): List<Schedule>
    fun delete(id: String)
}

class InMemoryScheduleStore : ScheduleStore {
    private val items = mutableListOf<Schedule>()
    private var nextId = 0L
    override fun save(instruction: String, hour: Int, minute: Int, days: Set<Int>?): Schedule {
        val schedule = Schedule(ScheduleIds.scheduleId(++nextId), instruction, hour, minute, days)
        items += schedule
        return schedule
    }
    override fun list(): List<Schedule> = items.toList()
    override fun delete(id: String) {
        ScheduleIds.schedulePid(id) ?: return
        items.removeAll { it.id == id }
    }
}

// --- Seam A: understand "every morning at 9AM check emails". ---
// Finds the first time-like bit (9AM, 7:30pm, 14:00); whatever is left
// over (minus filler words) is the instruction. Null = no time found.
fun parseSchedule(text: String): ParsedSchedule? {
    val time = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
        .find(text) ?: return null
    var hour = time.groupValues[1].toInt()
    val minute = time.groupValues[2].ifEmpty { "0" }.toInt()
    if (hour > 23 || minute > 59) return null
    when (time.groupValues[3].lowercase()) {
        "pm" -> if (hour < 12) hour += 12
        "am" -> if (hour == 12) hour = 0
    }
    val lower = text.lowercase()
    val days: Set<Int>? = when {
        "weekday" in lower -> setOf(1, 2, 3, 4, 5)
        "weekend" in lower -> setOf(6, 7)
        "monday" in lower -> setOf(1)
        "tuesday" in lower -> setOf(2)
        "wednesday" in lower -> setOf(3)
        "thursday" in lower -> setOf(4)
        "friday" in lower -> setOf(5)
        "saturday" in lower -> setOf(6)
        "sunday" in lower -> setOf(7)
        else -> null
    }
    var instruction = text.replace(time.value, " ")
    for (filler in listOf("every", "morning", "evening", "afternoon", "at", "on", "please")) {
        instruction = instruction.replace(Regex("\\b$filler\\b", RegexOption.IGNORE_CASE), " ")
    }
    for (day in listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "weekdays", "weekend", "daily", "day")) {
        instruction = instruction.replace(Regex("\\b$day\\b", RegexOption.IGNORE_CASE), " ")
    }
    // Leftover "am/pm/a.m./p.m." from the time itself is not the task.
    instruction = instruction.replace(Regex("(?<![A-Za-z0-9])[ap]\\.?m\\.?(?![A-Za-z0-9])", RegexOption.IGNORE_CASE), " ")
    instruction = instruction.replace(Regex("\\s+"), " ").trim()
    if (instruction.isBlank()) return null
    return ParsedSchedule(hour, minute, days, instruction)
}

// The "Save?" line the screen shows before saving, so a misparse is caught.
fun formatSchedulePreview(parsed: ParsedSchedule): String {
    val time = "%d:%02d".format(parsed.hour, parsed.minute)
    val when_ = if (parsed.days == null) "Every day" else "On days ${parsed.days.sorted().joinToString(",")}"
    return "Save? $when_ at $time — ${parsed.instruction}"
}

// --- Seam B: the notebook for routines. ---
fun createSchedule(store: ScheduleStore, instruction: String, hour: Int, minute: Int, days: Set<Int>? = null): Schedule =
    store.save(instruction, hour, minute, days)

fun listSchedules(store: ScheduleStore): List<Schedule> = store.list()

fun deleteSchedule(store: ScheduleStore, id: String) = store.delete(id)

// --- Seam C: should it run now? (fake clock in tests, real time on phone) ---
fun dueSchedules(store: ScheduleStore, now: FakeClock): List<Schedule> =
    store.list().filter { schedule ->
        schedule.hour == now.hour &&
            schedule.minute == now.minute &&
            (schedule.days == null || now.dayOfWeek in schedule.days)
    }

// --- Seam C2: next wall-clock fire in a zone (for re-arm). ---
// Pure decision logic: the next datetime in `zone` whose wall-clock time
// is hour:minute on an allowed day, strictly after `now`. A timezone
// change keeps the same wall-clock time but moves the instant, so re-arm
// must recompute in the new zone (DST plays no role: the zones we care
// about, e.g. Asia/Kolkata, have no DST). Null days = every day; an
// explicitly empty set never fires and returns null.
fun nextFireTime(schedule: Schedule, zone: ZoneId, now: ZonedDateTime): ZonedDateTime? {
    val zonedNow = now.withZoneSameInstant(zone)
    val time = LocalTime.of(schedule.hour, schedule.minute)
    for (offset in 0..7) {
        val date = zonedNow.toLocalDate().plusDays(offset.toLong())
        if (schedule.days != null && date.dayOfWeek.value !in schedule.days) continue
        val candidate = ZonedDateTime.of(date, time, zone)
        if (candidate.isAfter(zonedNow)) return candidate
    }
    return null
}

// Loop-created schedule side effects (ADR-0002): a create/cancel that the
// Task Execution ran must arm/disarm its alarm + Worker like the manual
// and held-approval paths. Pure decision; the ViewModel maps ARM/DISARM
// to register/cancel calls on the IO thread. Only an approved Success
// counts: reject/timeout arrive as Success(cancelled_by_user) with
// approved=false, and acting on those would arm/disarm a routine the
// user just rejected.
enum class LoopScheduleSideEffect { ARM, DISARM }

fun loopScheduleSideEffect(callName: String, approved: Boolean, result: ToolResult): LoopScheduleSideEffect? {
    if (!approved) return null
    if (result !is ToolResult.Success) return null
    return when (callName) {
        "create_schedule" -> LoopScheduleSideEffect.ARM
        "cancel_schedule" -> LoopScheduleSideEffect.DISARM
        else -> null
    }
}

// One routine = one background job: stable name per schedule id,
// so re-saving never creates a double.
fun uniqueWorkName(scheduleId: String): String = "schedule_$scheduleId"

// After a reboot, every active routine must be set again.
fun schedulesToRescheduleAfterReboot(store: ScheduleStore): List<String> =
    store.list().map { it.id }

// Stable note id per routine: two routines never overwrite each other's
// note, even with identical wording (text.hashCode would collide).
fun routineNotificationId(scheduleId: String): Int = scheduleId.hashCode()

// Foreground-service note id: distinct from the result note id so the
// ongoing "Running routine" note and the "Routine done" note never share
// a slot. Same stability contract as the result id.
fun routineForegroundNotificationId(scheduleId: String): Int = "running_$scheduleId".hashCode()

// --- Seam D: the words on the result note. ---
data class ScheduleNotification(val title: String, val text: String)

fun buildResultNotification(schedule: Schedule, result: String): ScheduleNotification =
    ScheduleNotification(
        title = "Routine done",
        text = "${schedule.instruction}: $result",
    )
