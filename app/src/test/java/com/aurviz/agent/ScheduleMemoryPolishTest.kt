package com.aurviz.agent

import com.aurviz.agent.ui.firesIn
import com.aurviz.agent.ui.formatRunDuration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #89 (part of #85): Schedules + Memory polish seams.
class ScheduleMemoryPolishTest {

    // restoreMemoryRow round-trips the FULL row (category, importance,
    // useCount) instead of the lossy delete+re-save.
    @Test
    fun restoreMemoryRowKeepsFullRow() {
        val store = InMemoryStore()
        val saved =
            store.save(
                "my boss is Priya",
                source = "you told me",
                category = "work",
                importance = 9,
                createdAt = 1000L,
                lastUsed = 2000L,
                useCount = 4,
            )
        store.delete(saved.id)
        val restored = restoreMemoryRow(store, saved)
        assertEquals("my boss is Priya", restored.text)
        assertEquals("you told me", restored.source)
        assertEquals("work", restored.category)
        assertEquals(9, restored.importance)
        assertEquals(1000L, restored.createdAt)
        assertEquals(2000L, restored.lastUsed)
        assertEquals(4, restored.useCount)
    }

    @Test
    fun titleCaseFacetCapitalizesEachWord() {
        assertEquals("You Told Me", titleCaseFacet("you told me"))
        assertEquals("Auto Saved", titleCaseFacet("auto-saved"))
        assertEquals("General", titleCaseFacet("general"))
        assertEquals("All", titleCaseFacet("All"))
    }

    @Test
    fun matchRangesFindsCaseInsensitiveHits() {
        val ranges = matchRanges("my boss is Priya, boss of bosses", "BOSS")
        assertEquals(3, ranges.size)
        assertEquals(3, ranges[0].first)
        assertEquals("boss", "my boss is Priya, boss of bosses".substring(ranges[1].first, ranges[1].last + 1))
    }

    @Test
    fun matchRangesEmptyQueryFindsNothing() {
        assertTrue(matchRanges("my boss is Priya", "  ").isEmpty())
        assertTrue(matchRanges("", "boss").isEmpty())
    }

    private fun at(hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, 11, 24, hour, minute, 0, 0, ZoneId.of("UTC"))

    @Test
    fun firesInFormatsHoursAndMinutes() {
        assertEquals("Fires in 3h 12m", firesIn(at(12, 0), at(8, 48)))
    }

    @Test
    fun firesInFormatsMinutesOnly() {
        assertEquals("Fires in 45m", firesIn(at(9, 45), at(9, 0)))
    }

    @Test
    fun firesInUnderAMinute() {
        assertEquals("Fires in under a minute", firesIn(at(9, 0).plusSeconds(30), at(9, 0)))
    }

    @Test
    fun firesInNullHasNoFireTime() {
        assertEquals("No upcoming fire time", firesIn(null, at(9, 0)))
    }

    @Test
    fun formatRunDurationFormatsMillisAndSeconds() {
        assertEquals("850 ms", formatRunDuration(850))
        assertEquals("1.2 s", formatRunDuration(1200))
        assertEquals("1m 5s", formatRunDuration(65_000))
    }

    // Source pins: no raw cron leaks, no morphing-pill confirm, and the
    // two distinct Memory empty states exist.
    private fun appFile(name: String): String {
        var dir = java.io.File(System.getProperty("user.dir"))
        while (true) {
            if (java.io.File(dir, "src/main/AndroidManifest.xml").exists()) break
            dir = dir.parentFile ?: throw AssertionError("no app module dir")
        }
        return java.io.File(dir, "src/main/java/com/aurviz/agent/ui/screens/$name").readText()
    }

    @Test
    fun schedulesScreenHasNoRawCronOrMorphingPill() {
        val screen = appFile("SchedulesScreen.kt")
        assertTrue(!screen.contains("formatScheduleCron"))
        assertTrue(!screen.contains("Confirm?"))
        assertTrue(screen.contains("Delete Schedule?"))
        assertTrue(screen.contains("firesIn("))
        assertTrue(screen.contains("Review Schedule"))
    }

    @Test
    fun memoryScreenHasTwoDistinctEmptyStates() {
        val screen = appFile("MemoryScreen.kt")
        assertTrue(screen.contains("No memories yet"))
        assertTrue(screen.contains("No matches"))
    }
}
