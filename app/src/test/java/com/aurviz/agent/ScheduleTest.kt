package com.aurviz.agent

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Session 3 Scheduling slice (red): parse-preview + CRUD + firing
// decision + result note. Fake store + fake clock — no real alarms,
// no Room, no WorkManager in tests.
class ScheduleTest {

    // --- Seam A: parse + preview ---

    @Test
    fun parsesMorningNineAm() {
        val parsed = parseSchedule("every morning at 9AM check emails")
        assertNotNull(parsed)
        assertEquals(9, parsed!!.hour)
        assertEquals(0, parsed.minute)
        assertTrue(parsed.instruction.contains("check emails"))
    }

    @Test
    fun parsesEveningHalfPast() {
        val parsed = parseSchedule("every evening at 7:30pm send summary")
        assertNotNull(parsed)
        assertEquals(19, parsed!!.hour)
        assertEquals(30, parsed.minute)
    }

    @Test
    fun noTimeReturnsNull() {
        assertNull(parseSchedule("check emails"))
    }

    @Test
    fun amPmLeftoversStayOutOfInstruction() {
        val parsed = parseSchedule("at 2:29 a.m. check emails")!!
        assertEquals(2, parsed.hour)
        assertEquals(29, parsed.minute)
        assertEquals("check emails", parsed.instruction)
    }

    // --- Seam A2: 12am/12pm edges (the hour==12 branch) ---

    @Test
    fun twelveAmIsMidnight() {
        val parsed = parseSchedule("wake me at 12am")!!
        assertEquals(0, parsed.hour)
        assertEquals(0, parsed.minute)
    }

    @Test
    fun twelvePmIsNoon() {
        val parsed = parseSchedule("lunch at 12pm")!!
        assertEquals(12, parsed.hour)
        assertEquals(0, parsed.minute)
    }

    @Test
    fun twelveThirtyAmAndPm() {
        assertEquals(0, parseSchedule("wake me at 12:30am")!!.hour)
        assertEquals(30, parseSchedule("wake me at 12:30am")!!.minute)
        assertEquals(12, parseSchedule("lunch at 12:30pm")!!.hour)
        assertEquals(30, parseSchedule("lunch at 12:30pm")!!.minute)
    }

    // --- Seam A3: next fire time in a zone (DST-free timezone change) ---

    @Test
    fun nextFireTodayWhenTimeStillAhead() {
        val schedule = Schedule("sched_1", "check emails", 9, 0)
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 11, 23, 7, 0, 0, 0, zone)
        val next = nextFireTime(schedule, zone, now)!!
        assertEquals(ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, zone), next)
    }

    @Test
    fun nextFireTomorrowWhenTimePassed() {
        val schedule = Schedule("sched_1", "check emails", 9, 0)
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, zone)
        val next = nextFireTime(schedule, zone, now)!!
        assertEquals(ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, zone), next)
    }

    @Test
    fun exactFireInstantRearmsNextDay() {
        val schedule = Schedule("sched_1", "check emails", 9, 0)
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, zone)
        val next = nextFireTime(schedule, zone, now)!!
        assertEquals(ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, zone), next)
    }

    @Test
    fun mondayOnlySkipsToMonday() {
        val schedule = Schedule("sched_1", "weekly report", 9, 0, days = setOf(1))
        val zone = ZoneId.of("Asia/Kolkata")
        // 2026-11-21 is a Saturday.
        val now = ZonedDateTime.of(2026, 11, 21, 10, 0, 0, 0, zone)
        val next = nextFireTime(schedule, zone, now)!!
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, zone), next)
    }

    @Test
    fun sameWallTimeIsADifferentInstantAfterTimezoneChange() {
        // DST-free zones: a traveller keeps 9:00 wall-clock, but the
        // instant moves. Re-arm must follow the zone, not a fixed offset.
        val schedule = Schedule("sched_1", "check emails", 9, 0)
        val now = ZonedDateTime.of(2026, 1, 15, 3, 0, 0, 0, ZoneOffset.UTC)
        val kolkata = nextFireTime(schedule, ZoneId.of("Asia/Kolkata"), now)!!
        val dubai = nextFireTime(schedule, ZoneId.of("Asia/Dubai"), now)!!
        assertEquals(9, kolkata.withZoneSameInstant(ZoneId.of("Asia/Kolkata")).hour)
        assertEquals(9, dubai.withZoneSameInstant(ZoneId.of("Asia/Dubai")).hour)
        assertEquals(Instant.parse("2026-01-15T03:30:00Z"), kolkata.toInstant())
        assertEquals(Instant.parse("2026-01-15T05:00:00Z"), dubai.toInstant())
        assertFalse(kolkata.toInstant() == dubai.toInstant())
    }

    @Test
    fun emptyDaysNeverFires() {
        val schedule = Schedule("sched_1", "nothing", 9, 0, days = emptySet())
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 11, 23, 7, 0, 0, 0, zone)
        assertNull(nextFireTime(schedule, zone, now))
    }

    @Test
    fun previewShowsTimeAndInstruction() {
        val parsed = parseSchedule("every morning at 9AM check emails")!!
        val preview = formatSchedulePreview(parsed)
        assertTrue(preview.contains("9:00") || preview.contains("09:00"))
        assertTrue(preview.contains("check emails"))
    }

    // --- Seam B: save / list / delete ---

    @Test
    fun saveThenListShowsIt() {
        val store = InMemoryScheduleStore()
        createSchedule(store, "check emails", 9, 0)
        assertEquals(1, listSchedules(store).size)
        assertEquals("check emails", listSchedules(store)[0].instruction)
    }

    @Test
    fun deleteStopsRoutine() {
        val store = InMemoryScheduleStore()
        val saved = createSchedule(store, "check emails", 9, 0)
        deleteSchedule(store, saved.id)
        assertTrue(listSchedules(store).isEmpty())
    }

    // --- Seam B2: Room adapter round-trips ids ("sched_12" <-> pid 12) ---

    private class FakeScheduleDao : ScheduleDao {
        private val rows = mutableListOf<ScheduleRow>()
        private var nextPid = 0L
        override fun listAll(): List<ScheduleRow> = rows.toList()
        override fun insert(row: ScheduleRow): Long {
            nextPid += 1
            rows += row.copy(pid = nextPid)
            return nextPid
        }
        override fun deleteById(pid: Long) {
            rows.removeAll { it.pid == pid }
        }
    }

    @Test
    fun roomStoreCrudRoundTripsIds() {
        val store = RoomScheduleStore(FakeScheduleDao())
        val saved = createSchedule(store, "check emails", 9, 0, days = setOf(1, 2, 3, 4, 5))
        assertTrue(saved.id.startsWith("sched_"))

        val listed = listSchedules(store)
        assertEquals(1, listed.size)
        assertEquals(saved.id, listed[0].id)
        assertEquals("check emails", listed[0].instruction)
        assertEquals(9, listed[0].hour)
        assertEquals(setOf(1, 2, 3, 4, 5), listed[0].days)

        deleteSchedule(store, saved.id)
        assertTrue(listSchedules(store).isEmpty())
    }

    @Test
    fun roomStoreDeleteIgnoresMalformedId() {
        val store = RoomScheduleStore(FakeScheduleDao())
        createSchedule(store, "check emails", 9, 0)
        deleteSchedule(store, "not-a-schedule-id")
        assertEquals(1, listSchedules(store).size)
    }

    // --- Seam C: firing decision on a fake clock ---

    @Test
    fun dailyFiresAtExactTime() {
        val store = InMemoryScheduleStore()
        createSchedule(store, "check emails", 9, 0)
        val due = dueSchedules(store, FakeClock(hour = 9, minute = 0, dayOfWeek = 1))
        assertEquals(1, due.size)
    }

    @Test
    fun notDueOneMinuteOff() {
        val store = InMemoryScheduleStore()
        createSchedule(store, "check emails", 9, 0)
        assertTrue(dueSchedules(store, FakeClock(9, 1, 1)).isEmpty())
        assertTrue(dueSchedules(store, FakeClock(8, 59, 1)).isEmpty())
    }

    @Test
    fun mondayOnlyNotDueTuesday() {
        val store = InMemoryScheduleStore()
        createSchedule(store, "weekly report", 9, 0, days = setOf(1))
        assertEquals(1, dueSchedules(store, FakeClock(9, 0, 1)).size)
        assertTrue(dueSchedules(store, FakeClock(9, 0, 2)).isEmpty())
    }

    @Test
    fun workNamesAreUniqueAndStable() {
        val store = InMemoryScheduleStore()
        val a = createSchedule(store, "one", 9, 0)
        val b = createSchedule(store, "two", 9, 0)
        assertFalse(uniqueWorkName(a.id) == uniqueWorkName(b.id))
        assertEquals(uniqueWorkName(a.id), uniqueWorkName(a.id))
    }

    @Test
    fun rebootReschedulesAllActive() {
        val store = InMemoryScheduleStore()
        val a = createSchedule(store, "one", 9, 0)
        val b = createSchedule(store, "two", 18, 0)
        val ids = schedulesToRescheduleAfterReboot(store).toSet()
        assertEquals(setOf(a.id, b.id), ids)
    }

    // --- Seam D: result note wording ---

    @Test
    fun resultNoteNamesRoutineAndResult() {
        val schedule = Schedule("sched_1", "check emails", 9, 0)
        val note = buildResultNotification(schedule, "3 important emails")
        assertTrue(note.title.isNotBlank())
        assertTrue(note.text.contains("check emails"))
        assertTrue(note.text.contains("3 important emails"))
    }

    @Test
    fun notificationIdsAreStableAndDistinct() {
        assertEquals(routineNotificationId("sched_1"), routineNotificationId("sched_1"))
        assertFalse(routineNotificationId("sched_1") == routineNotificationId("sched_2"))
    }

    @Test
    fun foregroundNoteIdIsStableAndSplitFromResultId() {
        assertEquals(routineForegroundNotificationId("sched_1"), routineForegroundNotificationId("sched_1"))
        assertFalse(routineForegroundNotificationId("sched_1") == routineNotificationId("sched_1"))
    }

    @Test
    fun loopCreateArmsAndCancelDisarmsOnlyOnApprovedSuccess() {
        val ok = ToolResult.Success(mapOf("ok" to true))
        val cancelled = ToolResult.Success(mapOf("status" to "cancelled_by_user"))
        val failed = ToolResult.Failure("nope", retryable = false)
        assertEquals(LoopScheduleSideEffect.ARM, loopScheduleSideEffect("create_schedule", true, ok))
        assertEquals(LoopScheduleSideEffect.DISARM, loopScheduleSideEffect("cancel_schedule", true, ok))
        // Reject/timeout report Success(cancelled_by_user) with approved=false:
        // a rejected cancel must not disarm, a rejected create must not arm.
        assertNull(loopScheduleSideEffect("create_schedule", false, cancelled))
        assertNull(loopScheduleSideEffect("cancel_schedule", false, cancelled))
        assertNull(loopScheduleSideEffect("create_schedule", true, failed))
        assertNull(loopScheduleSideEffect("cancel_schedule", true, failed))
        assertNull(loopScheduleSideEffect("send_email", true, ok))
        assertNull(loopScheduleSideEffect("search_emails", true, ok))
    }
}
