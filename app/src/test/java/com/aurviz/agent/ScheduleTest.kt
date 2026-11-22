package com.aurviz.agent

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
}
