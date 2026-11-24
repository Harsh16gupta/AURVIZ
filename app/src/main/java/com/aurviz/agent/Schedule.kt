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
// Finds the first time-like bit (9AM, 7:30pm, 14:00, 7:46 p.m., 1946);
// whatever is left over (minus filler words) is the instruction.
// Null = no time found. a.m./p.m. with periods normalize to am/pm
// before matching so "7:46 p.m." converts PM correctly instead of
// silently staying AM. Bare HHMM ("1946") splits into HH + MM.
fun parseSchedule(text: String): ParsedSchedule? {
    // Normalize dotted meridians first so the time regex sees them.
    // "p.m." / "p. m." / "P.M." -> "pm", same for am. Word-boundary
    // guarded so "camp" / "lamp" never match.
    val dottedMeridian = Regex("\\b([AaPp])\\s*\\.\\s*[Mm]\\s*\\.?", RegexOption.IGNORE_CASE)
    val normalized = dottedMeridian.replace(text) { m ->
        m.groupValues[1].lowercase() + "m"
    }
    var hour = -1
    var minute = 0
    var matchedText: String? = null

    // 1) HH:MM or H.MM with optional am/pm — "7:30pm", "19:46", "7.46 pm".
    val colon = Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE)
        .find(normalized)
    if (colon != null) {
        hour = colon.groupValues[1].toInt()
        minute = colon.groupValues[2].toInt()
        val meridian = colon.groupValues[3].lowercase()
        if (hour > 23 || minute > 59) return null
        when (meridian) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        matchedText = colon.value
    } else {
        // 2) Bare HHMM — "1946" -> 19:46, "746" -> 7:46.
        val hhmm = Regex("\\b(\\d{3,4})\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE)
            .find(normalized)
        if (hhmm != null) {
            val digits = hhmm.groupValues[1]
            val meridian = hhmm.groupValues[2].lowercase()
            val h: Int
            val m: Int
            if (digits.length == 3) {
                h = digits.substring(0, 1).toInt()
                m = digits.substring(1).toInt()
            } else {
                h = digits.substring(0, 2).toInt()
                m = digits.substring(2).toInt()
            }
            if (h <= 23 && m <= 59 && (meridian.isNotEmpty() || h >= 6 || m != 0)) {
                // Guard: bare 3-4 digit numbers are only times when they
                // carry a meridian or look clock-like; plain counts ("read
                // 200 emails") must not parse as times.
                hour = h
                minute = m
                when (meridian) {
                    "pm" -> if (hour < 12) hour += 12
                    "am" -> if (hour == 12) hour = 0
                }
                matchedText = hhmm.value
            }
        }
        if (hour < 0) {
            // 3) Bare hour + meridian — "9AM", "7 pm".
            val bare = Regex("(\\d{1,2})\\s*(am|pm)", RegexOption.IGNORE_CASE)
                .find(normalized) ?: return null
            hour = bare.groupValues[1].toInt()
            minute = 0
            if (hour > 23) return null
            when (bare.groupValues[2].lowercase()) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            matchedText = bare.value
        }
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
    // Strip the time tokens from the instruction. Colon times first
    // ("7:46 p.m."), else the exact HHMM/hour digits that produced this
    // parse — never a blind 3-digit strip that would eat "200 emails".
    var instruction = text
    val colonPat = Regex("\\b\\d{1,2}\\s*[:.]\\s*\\d{2}\\b")
    instruction = if (colonPat.containsMatchIn(instruction)) {
        colonPat.replaceFirst(instruction, " ")
    } else {
        val candidates = listOf(
            "%02d%02d".format(hour, minute),
            "%d%02d".format(hour, minute),
            hour.toString(),
        ).distinct()
        var tmp = instruction
        for (c in candidates) {
            if (Regex("\\b$c\\b").containsMatchIn(tmp)) {
                tmp = Regex("\\b$c\\b").replaceFirst(tmp, " ")
                break
            }
        }
        tmp
    }
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
    val when_ = if (parsed.days == null) "Every day" else "On days ${parsed.days.sorted().joinToString(",")}"
    return "Save? $when_ at ${formatScheduleTime(parsed.hour, parsed.minute)} — ${parsed.instruction}"
}

// Shared 12-hour shape with AM/PM for both preview lines so the two
// framings cannot drift ("7:46 PM", never ambiguous "19:46"/"07:46").
// Tests pin the "H:MM" substring, which the 12-hour form still contains.
fun formatScheduleTime(hour: Int, minute: Int): String {
    val h12 = when (hour % 12) {
        0 -> 12
        else -> hour % 12
    }
    val ampm = if (hour < 12) "AM" else "PM"
    return "$h12:%02d $ampm".format(minute)
}

// Ticket #69 (part of #65 T4): the typed/voice `unschedule <id>` fast path
// is preview-gated like create — no silent disarm. Pure helpers behind
// the ViewModel's pendingUnschedule card: normalize the typed id, resolve
// it against the store list, render the Stop? line. The screen's inline
// row keeps its own Delete/Keep confirm and deletes directly.
fun normalizeScheduleId(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.startsWith("sched_")) trimmed else "sched_$trimmed"
}

fun findScheduleByRawId(schedules: List<Schedule>, rawId: String): Schedule? {
    val id = normalizeScheduleId(rawId)
    return schedules.find { it.id == id }
}

// The "Stop?" line the screen shows before disarming, so a misheard id
// is caught. Same time shape as the create preview.
fun formatUnschedulePreview(schedule: Schedule): String {
    val time = formatScheduleTime(schedule.hour, schedule.minute)
    return "Stop? ${schedule.id} — ${schedule.instruction} at $time"
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
// and held-approval paths. Pure decision; the ViewModel maps Arm/Disarm
// to register/cancel calls on the IO thread. Only an approved Success
// counts: reject/timeout arrive as Success(cancelled_by_user) with
// approved=false, and acting on those would arm/disarm a routine the
// user just rejected.

// #79 T2: single owned seam for all approved-write re-arm. Both
// AgentViewModel call sites (syncAlarmsForApproved for held approvals,
// syncLoopScheduleSideEffect for foreground loop) delegate here instead
// of mirroring "create_schedule"/"cancel_schedule" strings by hand.
sealed interface ScheduleSideEffect {
    data class Arm(val scheduleId: String) : ScheduleSideEffect
    data class Disarm(val scheduleId: String) : ScheduleSideEffect
    data object None : ScheduleSideEffect
}

fun onApprovedWrite(
    toolName: String,
    arguments: Map<String, Any?>,
    approved: Boolean,
    result: ToolResult,
): ScheduleSideEffect {
    if (!approved || result !is ToolResult.Success) return ScheduleSideEffect.None
    return when (toolName) {
        "create_schedule" -> {
            val newId = (result.data as? Map<*, *>)?.get("id") as? String
                ?: return ScheduleSideEffect.None
            ScheduleSideEffect.Arm(newId)
        }
        "cancel_schedule" -> {
            val target = arguments["schedule_id"] as? String
                ?: return ScheduleSideEffect.None
            ScheduleSideEffect.Disarm(target)
        }
        else -> ScheduleSideEffect.None
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
