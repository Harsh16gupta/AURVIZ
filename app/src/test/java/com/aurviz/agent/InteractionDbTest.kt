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
import com.aurviz.agent.ui.SessionSummary
import com.aurviz.agent.ui.components.ConfirmationStatus
import com.aurviz.agent.ui.filterSessionSummaries
import com.aurviz.agent.ui.formatSessionTranscriptMarkdown
import com.aurviz.agent.ui.groupPastInteractions
import com.aurviz.agent.ui.hydrateLatestSession
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
        assertEquals(6, AppMigrations.size)
        val last = AppMigrations.last()
        assertEquals(6, last.startVersion)
        assertEquals(7, last.endVersion)
        assertTrue(AppMigrations.contains(MIGRATION_5_6))
        assertTrue(AppMigrations.contains(MIGRATION_6_7))
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
                providerUsed = "deepseek",
                failed = false,
            )
        val row = rec.toRow("s_1")
        assertEquals("s_1", row.sessionId)
        assertEquals("check mail", row.command)
        assertEquals("done", row.responseText)
        assertEquals("deepseek", row.provider)
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

    // Ticket #106 (T1): cold-start active Session hydration from Room rows.

    private fun sessionRow(sessionId: String, command: String, response: String, timestampMs: Long) =
        InteractionRow(
            sessionId = sessionId,
            command = command,
            responseText = response,
            provider = "deepseek",
            timestampMs = timestampMs,
            failed = false,
        )

    @Test
    fun hydrateLatestSessionRestoresMostRecentSession() {
        // listRecent returns newest-first.
        val rowsDesc =
            listOf(
                sessionRow("s_new", "follow up", "done 2", 2000L),
                sessionRow("s_new", "first q", "done 1", 1000L),
                sessionRow("s_old", "old q", "old done", 500L),
            )
        val hydrated = hydrateLatestSession(rowsDesc, "s_fallback")
        assertEquals("s_new", hydrated.sessionId)
        assertEquals(2, hydrated.sessionTurns)
        assertEquals(listOf("first q", "follow up"), hydrated.interactions.map { it.command })
        assertEquals(4, hydrated.transcript.size)
        assertEquals("user", hydrated.transcript[0].role)
        assertEquals("first q", hydrated.transcript[0].content)
        assertEquals("assistant", hydrated.transcript[1].role)
        assertEquals("done 1", hydrated.transcript[1].content)
        assertEquals("s_new", hydrated.sessions.first().sessionId)
    }

    @Test
    fun hydrateLatestSessionFallsBackWhenEmpty() {
        val hydrated = hydrateLatestSession(emptyList(), "s_fallback")
        assertEquals("s_fallback", hydrated.sessionId)
        assertEquals(0, hydrated.sessionTurns)
        assertTrue(hydrated.interactions.isEmpty())
        assertTrue(hydrated.transcript.isEmpty())
        assertTrue(hydrated.sessions.isEmpty())
    }

    // Ticket #109 (T4): SideDrawer Session search filter.

    private fun summary(id: String, title: String) =
        SessionSummary(sessionId = id, title = title, lastTimestampMs = 1000L, interactionCount = 1)

    @Test
    fun filterBlankQueryReturnsAllSummaries() {
        val sessions = listOf(summary("s1", "Check mail"), summary("s2", "Plan trip"))
        assertEquals(sessions, filterSessionSummaries(sessions, ""))
        assertEquals(sessions, filterSessionSummaries(sessions, "   "))
    }

    @Test
    fun filterMatchesTitleCaseInsensitively() {
        val sessions = listOf(summary("s1", "Check Mail"), summary("s2", "Plan trip"))
        assertEquals(listOf("s1"), filterSessionSummaries(sessions, "mail").map { it.sessionId })
        assertEquals(listOf("s1"), filterSessionSummaries(sessions, "CHECK").map { it.sessionId })
        assertEquals(listOf("s2"), filterSessionSummaries(sessions, "  trip  ").map { it.sessionId })
    }

    @Test
    fun filterNoMatchReturnsEmpty() {
        val sessions = listOf(summary("s1", "Check mail"))
        assertTrue(filterSessionSummaries(sessions, "zzz").isEmpty())
    }

    // Ticket #109 (T4): Markdown Session transcript exporter.

    @Test
    fun transcriptEmptyInteractionsShowsFallbackLine() {
        assertEquals(
            "# Aurviz Session (s_1)\n\nNo Interactions recorded in this Session.",
            formatSessionTranscriptMarkdown("s_1", emptyList()),
        )
    }

    @Test
    fun transcriptFormatsCommandAndResponse() {
        val record = record("check mail", 1000L)
        val md = formatSessionTranscriptMarkdown("s_1", listOf(record))
        assertTrue(md.startsWith("# Aurviz Session"))
        assertTrue(md.contains("## Interaction 1: check mail"))
        assertTrue(md.contains("done"))
    }

    @Test
    fun transcriptFormatsMemoryPlanConfirmationAndProvider() {
        val record =
            InteractionRecord(
                id = "in_1",
                command = "send update",
                timestampMs = 1000L,
                recalledMemories = listOf("boss is Priya"),
                steps = listOf(TimelineStep("Tool 1 of 1: send_email", StepState.DONE, toolName = "send_email", argsSummary = "to: a@b.c")),
                responseText = "sent",
                providerUsed = "deepseek",
                confirmationTitle = "Send email to a@b.c",
                confirmationStatus = ConfirmationStatus.Approved,
            )
        val md = formatSessionTranscriptMarkdown("s_1", listOf(record))
        assertTrue(md.contains("- **Recalled Memory:** boss is Priya"))
        assertTrue(md.contains("- **Plan:**"))
        assertTrue(md.contains("[DONE]"))
        assertTrue(md.contains("- **Confirmation (Approved):** Send email to a@b.c"))
        assertTrue(md.contains("- **Agent Provider:** DeepSeek"))
        assertTrue(md.contains("sent"))
    }

    @Test
    fun transcriptSeparatesMultipleInteractions() {
        val md = formatSessionTranscriptMarkdown("s_1", listOf(record("one", 1000L), record("two", 2000L)))
        assertTrue(md.contains("## Interaction 1: one"))
        assertTrue(md.contains("## Interaction 2: two"))
        assertTrue(md.contains("---"))
    }

    @Test
    fun transcriptCopyHasNoBannedWords() {
        val record =
            InteractionRecord(
                id = "in_1",
                command = "check mail",
                timestampMs = 1000L,
                recalledMemories = listOf("boss is Priya"),
                steps = listOf(TimelineStep("Tool 1 of 1: send_email", StepState.DONE, toolName = "send_email")),
                responseText = "sent",
                providerUsed = "deepseek",
                confirmationTitle = "Send email to a@b.c",
                confirmationStatus = ConfirmationStatus.Approved,
            )
        val md = formatSessionTranscriptMarkdown("s_1", listOf(record))
        val banned = listOf("history", "chatbot", "workflow", "alarm", "timer", "cron", "dictation", "job run")
        banned.forEach { word ->
            assertFalse("banned copy '$word' in transcript", md.contains(word, ignoreCase = true))
        }
    }
}
