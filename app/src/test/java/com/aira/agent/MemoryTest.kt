package com.aira.agent

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Session 1 slice 1 (red): remember + recall.
// No internet, no database — just a plain in-memory list.
class MemoryTest {

    @Test
    fun rememberedFactCanBeRecalled() {
        val store = InMemoryStore()
        rememberFact(store, "my boss is Priya", source = "you told me")
        val found = recallFor(store, "who is my boss?")
        assertEquals(1, found.size)
        assertEquals("my boss is Priya", found[0].text)
    }

    @Test
    fun rememberSameFactTwiceKeepsOne() {
        val store = InMemoryStore()
        rememberFact(store, "my name is harsh", source = "you told me")
        rememberFact(store, "my name is harsh", source = "you told me")
        assertEquals(1, store.list().size)
    }

    @Test
    fun recallShowsSource() {
        val store = InMemoryStore()
        rememberFact(store, "my boss is Priya", source = "you told me")
        val found = recallFor(store, "who is my boss?")
        assertEquals("you told me", found[0].source)
    }

    @Test
    fun unrelatedQueryFindsNothing() {
        val store = InMemoryStore()
        rememberFact(store, "my boss is Priya", source = "you told me")
        assertTrue(recallFor(store, "favorite pizza").isEmpty())
    }

    @Test
    fun forgetPreviewListsWithoutDeleting() {
        val store = InMemoryStore()
        rememberFact(store, "my boss is Priya", source = "you told me")
        rememberFact(store, "my boss likes tea", source = "auto-saved")
        rememberFact(store, "I like pizza", source = "you told me")
        val preview = forgetPreview(store, "boss")
        assertEquals(2, preview.size)
        assertEquals(3, store.list().size)
    }

    @Test
    fun forgetConfirmedDeletesOnlyPreviewed() {
        val store = InMemoryStore()
        rememberFact(store, "my boss is Priya", source = "you told me")
        rememberFact(store, "I like pizza", source = "you told me")
        forgetPreview(store, "boss").forEach { store.delete(it.id) }
        assertTrue(recallFor(store, "who is my boss?").isEmpty())
        assertEquals(1, recallFor(store, "pizza").size)
    }

    @Test
    fun recallRanksHigherWordOverlapFirst() {
        val store = InMemoryStore()
        rememberFact(store, "my boss is Priya", source = "you told me")
        rememberFact(store, "my boss likes tea", source = "auto-saved")
        val found = recallFor(store, "who is my boss Priya?")
        assertEquals(2, found.size)
        assertEquals("my boss is Priya", found[0].text)
    }

    @Test
    fun recallBreaksOverlapTiesByRecencyNewestFirst() {
        val store = InMemoryStore()
        rememberFact(store, "my boss is Priya", source = "you told me")
        rememberFact(store, "my boss likes tea", source = "auto-saved")
        val found = recallFor(store, "boss")
        assertEquals(2, found.size)
        assertEquals("my boss likes tea", found[0].text)
    }

    // Ticket #31 slice 1 (red): new row columns with sane defaults.
    @Test
    fun newMemoryColumnsHaveSaneDefaults() {
        val store = InMemoryStore()
        val memory = store.save("my boss is Priya", source = "you told me")
        assertEquals("general", memory.category)
        assertEquals(0, memory.importance)
        assertEquals(0, memory.useCount)
    }

    @Test
    fun saveAcceptsCategoryAndImportance() {
        val store = InMemoryStore()
        val memory = store.save("my boss is Priya", source = "you told me", category = "work", importance = 5)
        assertEquals("work", memory.category)
        assertEquals(5, memory.importance)
    }

    // Ticket #31 slice 2 (red): migration 2 -> 3 adds the new columns.
    @Test
    fun migration23AddsNewColumnsWithDefaults() {
        val statements = mutableListOf<String>()
        val handler = { _: Any, method: java.lang.reflect.Method, args: Array<out Any>? ->
            if (method.name == "execSQL") statements += args!![0] as String
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
        val db =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                handler,
            ) as SupportSQLiteDatabase
        MIGRATION_2_3.migrate(db)
        assertEquals(5, statements.size)
        val joined = statements.joinToString("\n")
        assertTrue(joined.contains("ADD COLUMN `category` TEXT NOT NULL DEFAULT 'general'"))
        assertTrue(joined.contains("ADD COLUMN `importance` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(joined.contains("ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(joined.contains("ADD COLUMN `lastUsed` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(joined.contains("ADD COLUMN `useCount` INTEGER NOT NULL DEFAULT 0"))
    }

    // Ticket #31 slice 3 (red): importance boost orders equal-overlap hits.
    @Test
    fun recallRanksHigherImportanceFirstOnEqualOverlap() {
        val store = InMemoryStore()
        store.save("my boss likes tea", source = "auto-saved", importance = 0, createdAt = 1000L)
        store.save("my boss is Priya", source = "you told me", importance = 9, createdAt = 1000L)
        val found = recallFor(store, "boss")
        assertEquals(2, found.size)
        assertEquals("my boss is Priya", found[0].text)
    }

    // Ticket #31 slice 4 (red): recency orders equal-overlap, equal-importance hits.
    @Test
    fun recallRanksNewerFirstOnEqualOverlapAndImportance() {
        val store = InMemoryStore()
        store.save("my boss is Priya", source = "you told me", createdAt = 1000L)
        store.save("my boss likes tea", source = "auto-saved", createdAt = 2000L)
        val found = recallFor(store, "boss")
        assertEquals(2, found.size)
        assertEquals("my boss likes tea", found[0].text)
    }

    // Ticket #31 no-regress guard: keyword overlap still beats both boosts.
    @Test
    fun recallOverlapStillBeatsImportanceAndRecency() {
        val store = InMemoryStore()
        store.save("my boss is Priya", source = "you told me", importance = 0, createdAt = 1000L)
        store.save("my boss likes tea today", source = "auto-saved", importance = 9, createdAt = 2000L)
        val found = recallFor(store, "who is my boss Priya?")
        assertEquals(2, found.size)
        assertEquals("my boss is Priya", found[0].text)
    }

    // Ticket #31 slice 5 (red): usage tracking bumps lastUsed/useCount.
    @Test
    fun markUsedBumpsLastUsedAndUseCount() {
        val store = InMemoryStore()
        val saved = store.save("my boss is Priya", source = "you told me", createdAt = 1000L)
        store.markUsed(saved.id, nowMs = 5000L)
        val updated = store.list().first { it.id == saved.id }
        assertEquals(5000L, updated.lastUsed)
        assertEquals(1, updated.useCount)
    }

    @Test
    fun recentlyUsedMemoryWinsRecencyTie() {
        val store = InMemoryStore()
        val old = store.save("my boss is Priya", source = "you told me", createdAt = 1000L)
        store.save("my boss likes tea", source = "auto-saved", createdAt = 1000L)
        store.markUsed(old.id, nowMs = 9000L)
        val found = recallFor(store, "boss")
        assertEquals("my boss is Priya", found[0].text)
    }

    // Ticket #31 slice 6 (red): offline FTS fallback with prefix matching.
    @Test
    fun offlineFtsFallbackMatchesWordPrefixes() {
        val store = InMemoryStore()
        store.save("my boss is Priya", source = "you told me")
        // Online keyword path needs whole-word overlap: "bos" matches nothing.
        assertTrue(recallFor(store, "bos", online = true).isEmpty())
        // Offline FTS path treats terms as prefixes (FTS MATCH 'bos*').
        val found = recallFor(store, "bos", online = false)
        assertEquals(1, found.size)
        assertEquals("my boss is Priya", found[0].text)
    }

    @Test
    fun offlineFtsFallbackRequiresAllTermsAndRanksWithBoosts() {
        val store = InMemoryStore()
        store.save("my boss likes tea", source = "auto-saved", importance = 0, createdAt = 1000L)
        store.save("my boss is Priya", source = "you told me", importance = 9, createdAt = 1000L)
        store.save("I like pizza", source = "you told me", importance = 9, createdAt = 3000L)
        val found = recallFor(store, "boss priya", online = false)
        assertEquals(1, found.size)
        assertEquals("my boss is Priya", found[0].text)
    }

    @Test
    fun ftsQueryBuilderProducesPrefixTerms() {
        assertEquals("boss* priya*", toFtsQuery("boss Priya"))
    }

    @Test
    fun storeSearchFtsUsesPrefixTermModel() {
        val store = InMemoryStore()
        store.save("my boss is Priya", source = "you told me")
        store.save("I like pizza", source = "you told me")
        val found = store.searchFts("bos*")
        assertEquals(1, found.size)
        assertEquals("my boss is Priya", found[0].text)
    }

    @Test
    fun roomFtsQueryUsesMatchOverMemoriesFts() {
        // The offline index is a real SQLite FTS MATCH, not a table scan.
        // Room's @Query is CLASS-retention (invisible to reflection), so pin
        // the source like the manifest tests do.
        var dir = java.io.File(System.getProperty("user.dir"))
        while (true) {
            if (java.io.File(dir, "src/main/AndroidManifest.xml").exists()) break
            dir = dir.parentFile ?: throw AssertionError("no app module dir")
        }
        val db = java.io.File(dir, "src/main/java/com/aira/agent/MemoryDb.kt").readText()
        assertTrue(db.contains("memoriesFts MATCH :query"))
        assertTrue(db.contains("@Fts4"))
        assertTrue(db.contains("MIGRATION_4_5"))
        assertTrue(db.contains("USING FTS4"))
    }

    @Test
    fun provenanceColumnsDefaultToNull() {
        val store = InMemoryStore()
        val memory = store.save("my boss is Priya", source = "you told me")
        assertEquals(null, memory.originId)
        assertEquals(null, memory.originSnippet)
        assertEquals(null, memory.originTimestamp)
    }

    @Test
    fun saveAndRememberFactRoundTripProvenance() {
        val store = InMemoryStore()
        val saved =
            rememberFact(
                store,
                "my boss is Priya",
                source = "you told me",
                originId = "in_42",
                originSnippet = "remember my boss is Priya",
                originTimestamp = 1700000000L,
            )
        assertEquals("in_42", saved.originId)
        assertEquals("remember my boss is Priya", saved.originSnippet)
        assertEquals(1700000000L, saved.originTimestamp)
        val listed = store.list().first()
        assertEquals("in_42", listed.originId)
        assertEquals("remember my boss is Priya", listed.originSnippet)
        assertEquals(1700000000L, listed.originTimestamp)
    }

    @Test
    fun migration67AddsNullableProvenanceColumns() {
        val statements = mutableListOf<String>()
        val handler = { _: Any, method: java.lang.reflect.Method, args: Array<out Any>? ->
            if (method.name == "execSQL") statements += args!![0] as String
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
        val db =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                handler,
            ) as SupportSQLiteDatabase
        MIGRATION_6_7.migrate(db)
        assertEquals(3, statements.size)
        val joined = statements.joinToString("\n")
        assertTrue(joined.contains("ALTER TABLE `memories` ADD COLUMN `originId` TEXT"))
        assertTrue(joined.contains("ADD COLUMN `originSnippet` TEXT"))
        assertTrue(joined.contains("ADD COLUMN `originTimestamp` INTEGER"))
    }

    @Test
    fun roomMemoryStoreRoundTripsProvenanceThroughDao() {
        val rows = mutableListOf<MemoryRow>()
        var nextPid = 0L
        val dao =
            object : MemoryDao {
                override fun listAll(): List<MemoryRow> = rows.toList()

                override fun searchFts(query: String): List<MemoryRow> = rows.toList()

                override fun insert(row: MemoryRow): Long {
                    nextPid += 1
                    rows += row.copy(pid = nextPid)
                    return nextPid
                }

                override fun deleteById(pid: Long) {
                    rows.removeAll { it.pid == pid }
                }

                override fun markUsed(pid: Long, nowMs: Long) {
                }
            }
        val store = RoomMemoryStore(dao)
        store.save(
            "my boss is Priya",
            source = "you told me",
            originId = "s_1",
            originSnippet = "boss is Priya",
            originTimestamp = 12345L,
        )
        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("s_1", listed[0].originId)
        assertEquals("boss is Priya", listed[0].originSnippet)
        assertEquals(12345L, listed[0].originTimestamp)
        val fts = store.searchFts("boss*")
        assertEquals(1, fts.size)
        assertEquals("s_1", fts[0].originId)
        assertEquals("boss is Priya", fts[0].originSnippet)
        assertEquals(12345L, fts[0].originTimestamp)
    }
}
