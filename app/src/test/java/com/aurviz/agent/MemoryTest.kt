package com.aurviz.agent

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
    fun offlineQueueReplaysOnceWithoutDuplicates() {
        val store = InMemoryStore()
        val outbox = Outbox()
        outbox.queue("my boss is Priya", "you told me")
        outbox.queue("standup at 9am", "you told me")
        assertEquals(2, outbox.replay(store))
        assertEquals(1, recallFor(store, "who is my boss?").size)
        assertEquals(0, outbox.replay(store))
        assertEquals(2, store.list().size)
    }
}
