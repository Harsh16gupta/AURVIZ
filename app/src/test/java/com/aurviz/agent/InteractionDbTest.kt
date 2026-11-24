package com.aurviz.agent

import androidx.sqlite.db.SupportSQLiteDatabase
import com.aurviz.agent.ui.CLOSE_SESSIONS_DESCRIPTION
import com.aurviz.agent.ui.GROUP_PREVIOUS_7_DAYS
import com.aurviz.agent.ui.GROUP_TODAY
import com.aurviz.agent.ui.InteractionRecord
import com.aurviz.agent.ui.NEW_SESSION_LABEL
import com.aurviz.agent.ui.NO_PAST_SESSIONS_LINE
import com.aurviz.agent.ui.OPEN_SESSIONS_DESCRIPTION
import com.aurviz.agent.ui.PAST_INTERACTIONS_TITLE
import com.aurviz.agent.ui.groupPastInteractions
import com.aurviz.agent.ui.toRecord
import com.aurviz.agent.ui.toRow
import java.lang.reflect.Proxy
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #92: Interaction persistence (v5→v6 + hydrate/persist seam),
// Sessions drawer grouping + copy, Memory dedup and recall analytics.
// Behavior only: migration SQL, pure grouping, kernel-visible recall.
class InteractionDbTest {

    private fun proxyDb(statements: MutableList<String>): SupportSQLiteDatabase {
        val handler = { _: Any, method: java.lang.reflect.Method, args: Array<out Any>? ->
            if (method.name == "execSQL") statements += args!![0] as String
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler,
        ) as SupportSQLiteDatabase
    }

    // --- migration seam: MIGRATION_5_6 statements ---

    @Test
    fun migration56CreatesInteractionsTableAndRunColumns() {
        val statements = mutableListOf<String>()
        MIGRATION_5_6.migrate(proxyDb(statements))
        assertEquals(5, statements.size)
        val joined = statements.joinToString("\n")
        assertTrue(joined.contains("CREATE TABLE IF NOT EXISTS `interactions`"))
        assertTrue(joined.contains("`sessionId` TEXT NOT NULL"))
        assertTrue(joined.contains("`command` TEXT NOT NULL"))
        assertTrue(joined.contains("`responseText` TEXT NOT NULL"))
        assertTrue(joined.contains("`provider` TEXT"))
        assertTrue(joined.contains("`timestampMs` INTEGER NOT NULL"))
        assertTrue(joined.contains("`failed` INTEGER NOT NULL"))
        assertTrue(joined.contains("ALTER TABLE `schedule_runs` ADD COLUMN `provider` TEXT"))
        assertTrue(joined.contains("ADD COLUMN `planMs` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(joined.contains("ADD COLUMN `toolMs` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(joined.contains("ADD COLUMN `recallMs` INTEGER NOT NULL DEFAULT 0"))
    }

    // Both open sites (AgentViewModel + ScheduleWork) build from this one
    // array, so registering here registers at both.
    @Test
    fun appMigrationsRegister56After45() {
        assertEquals(5, AppMigrations.size)
        val last = AppMigrations.last()
        assertEquals(5, last.startVersion)
        assertEquals(6, last.endVersion)
        assertTrue(AppMigrations.contains(MIGRATION_5_6))
    }

    // --- drawer grouping seam ---

    private val dayMs = 24L * 60 * 60 * 1000
    private val nowMs = 1_800_000_000_000L

    private fun record(command: String, timestampMs: Long) =
        InteractionRecord(id = "in_$timestampMs", command = command, timestampMs = timestampMs, responseText = "done")

    @Test
    fun drawerGroupsTodayAndPrevious7DaysNewestFirst() {
        val old = record("old", nowMs - 8 * dayMs)
        val prev = record("prev", nowMs - 2 * dayMs)
        val today = record("today", nowMs)
        val groups = groupPastInteractions(listOf(old, prev, today), nowMs)
        assertEquals(listOf("today"), groups.today.map { it.command })
        assertEquals(listOf("prev"), groups.previous7Days.map { it.command })
    }

    @Test
    fun drawerDropsRowsOlderThan7Days() {
        val groups = groupPastInteractions(listOf(record("old", nowMs - 8 * dayMs)), nowMs)
        assertTrue(groups.today.isEmpty())
        assertTrue(groups.previous7Days.isEmpty())
    }

    @Test
    fun drawerBoundaryRecordAtMidnightCountsAsToday() {
        val startOfToday =
            Calendar.getInstance().let { cal ->
                cal.timeInMillis = nowMs
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
        val groups = groupPastInteractions(listOf(record("midnight", startOfToday)), nowMs)
        assertEquals(listOf("midnight"), groups.today.map { it.command })
        assertTrue(groups.previous7Days.isEmpty())
    }

    @Test
    fun drawerCopyHasNoBannedWords() {
        val banned = listOf("history", "chatbot", "assistant", "workflow", "alarm", "timer", "cron", "dictation", "job run", "log")
        val labels =
            listOf(
                PAST_INTERACTIONS_TITLE,
                GROUP_TODAY,
                GROUP_PREVIOUS_7_DAYS,
                NEW_SESSION_LABEL,
                OPEN_SESSIONS_DESCRIPTION,
                CLOSE_SESSIONS_DESCRIPTION,
                NO_PAST_SESSIONS_LINE,
            )
        assertEquals(7, labels.size)
        labels.forEach { label ->
            banned.forEach { word ->
                assertFalse("banned copy '$word' in '$label'", label.contains(word, ignoreCase = true))
            }
        }
    }

    // --- Room round-trip seam ---

    @Test
    fun interactionRowRoundTripsThroughRecord() {
        val rec =
            InteractionRecord(
                id = "in_1",
                command = "check mail",
                timestampMs = 1234L,
                responseText = "done",
                providerUsed = "gemini",
                failed = false,
            )
        val row = rec.toRow("s_1")
        assertEquals("s_1", row.sessionId)
        assertEquals("check mail", row.command)
        assertEquals("done", row.responseText)
        assertEquals("gemini", row.provider)
        assertEquals(1234L, row.timestampMs)
        assertFalse(row.failed)
        val back = row.copy(pid = 7).toRecord()
        assertEquals("room_interaction_7", back.id)
        assertEquals(rec.command, back.command)
        assertEquals(rec.responseText, back.responseText)
        assertEquals(rec.providerUsed, back.providerUsed)
        assertEquals(rec.timestampMs, back.timestampMs)
        assertEquals(rec.failed, back.failed)
    }

    // --- Memory dedup seam (ticket #92: lower(trim(text))) ---

    @Test
    fun dedupIgnoresCaseAndSurroundingWhitespace() {
        val store = InMemoryStore()
        rememberFact(store, "my boss is Priya", source = "you told me")
        rememberFact(store, "  MY BOSS IS PRIYA  ", source = "you told me")
        assertEquals(1, store.list().size)
    }

    @Test
    fun dedupStillSeparatesDifferentSources() {
        val store = InMemoryStore()
        rememberFact(store, "my boss is Priya", source = "you told me")
        rememberFact(store, "my boss is Priya", source = "auto-saved")
        assertEquals(2, store.list().size)
    }

    // --- recall analytics seam (search_memory bumps useCount/recency) ---

    private fun kernelWithMemory(store: MemoryStore): ToolRegistry =
        ToolRegistry().apply {
            for (tool in memoryTools(store)) register(tool)
        }

    @Test
    fun searchMemoryBumpsUseCountOnHitsOnly() {
        val store = InMemoryStore()
        store.save("my boss is Priya", source = "you told me", createdAt = 1000L)
        store.save("my boss likes tea", source = "auto-saved", createdAt = 1000L)
        val found = kernelWithMemory(store).execute("search_memory", mapOf("query" to "priya"))
        assertTrue(found is ToolResult.Success)
        assertEquals(1, store.list().first { it.text == "my boss is Priya" }.useCount)
        assertEquals(0, store.list().first { it.text == "my boss likes tea" }.useCount)
    }

    @Test
    fun searchMemoryRecallImprovesWithReuse() {
        val store = InMemoryStore()
        store.save("my boss is Priya", source = "you told me", createdAt = 1000L)
        store.save("my boss likes tea", source = "auto-saved", createdAt = 2000L)
        // Baseline: the newer row wins the recency tie-break.
        assertEquals("my boss likes tea", recallFor(store, "boss")[0].text)
        // Recalling only the Priya row stamps its lastUsed to now.
        kernelWithMemory(store).execute("search_memory", mapOf("query" to "priya"))
        assertEquals("my boss is Priya", recallFor(store, "boss")[0].text)
    }
}
