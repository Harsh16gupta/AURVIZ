package com.aira.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #78 T1: one pid codec for the two Schedule-owned prefixes.
// Pins the contract: Schedule save/list/delete and PendingApproval
// save/list/take round-trip ids through ScheduleIds, days survive the
// Schedule round-trip, and malformed ids are no-ops, never crashes.
// Memory's room_ prefix and numeric schedule_runs pids stay untouched.
class ScheduleIdsTest {

    // --- Seam 1: the codec itself round-trips both prefixes. ---

    @Test
    fun scheduleCodecRoundTrips() {
        assertEquals("sched_12", ScheduleIds.scheduleId(12))
        assertEquals(12L, ScheduleIds.schedulePid("sched_12"))
    }

    @Test
    fun approvalCodecRoundTrips() {
        assertEquals("approval_7", ScheduleIds.approvalId(7))
        assertEquals(7L, ScheduleIds.approvalPid("approval_7"))
    }

    @Test
    fun scheduleCodecRejectsMalformedAndForeignPrefix() {
        assertNull(ScheduleIds.schedulePid(""))
        assertNull(ScheduleIds.schedulePid("not-a-schedule-id"))
        assertNull(ScheduleIds.schedulePid("sched_"))
        assertNull(ScheduleIds.schedulePid("sched_abc"))
        assertNull(ScheduleIds.schedulePid("sched_1 "))
        assertNull(ScheduleIds.schedulePid("approval_1"))
    }

    @Test
    fun approvalCodecRejectsMalformedAndForeignPrefix() {
        assertNull(ScheduleIds.approvalPid(""))
        assertNull(ScheduleIds.approvalPid("bogus"))
        assertNull(ScheduleIds.approvalPid("approval_"))
        assertNull(ScheduleIds.approvalPid("approval_xyz"))
        assertNull(ScheduleIds.approvalPid("sched_1"))
    }

    // --- Seam 2: Schedule save/list/delete round-trips, days preserved. ---

    @Test
    fun inMemoryScheduleRoundTripsDaysThroughCodec() {
        val store = InMemoryScheduleStore()
        val saved = store.save("check emails", 9, 0, days = setOf(1, 2, 3, 4, 5))
        assertTrue(saved.id.startsWith("sched_"))
        assertEquals(saved.id, store.list().single().id)
        assertEquals(setOf(1, 2, 3, 4, 5), store.list().single().days)
        store.delete(saved.id)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun inMemoryScheduleDeleteToleratesMalformedId() {
        val store = InMemoryScheduleStore()
        store.save("check emails", 9, 0)
        store.delete("not-a-schedule-id")
        store.delete("")
        assertEquals(1, store.list().size)
    }

    // --- Seam 3: PendingApproval save/list/take round-trips through codec. ---

    private fun held() = PendingApproval(
        scheduleId = "sched_1",
        toolName = "send_email",
        arguments = mapOf("to" to "friend@work.com", "subject" to "Hi", "body" to "hello"),
        reason = "scheduled run: sensitive tool held for an in-app Confirmation decision, never executed",
    )

    @Test
    fun inMemoryApprovalRoundTripsIdThroughCodec() {
        val store = InMemoryPendingApprovalStore()
        val saved = store.save(held())
        assertEquals(saved.id, ScheduleIds.approvalId(ScheduleIds.approvalPid(saved.id)!!))
        assertEquals(saved.id, store.list().single().id)
        assertEquals(saved.id, store.take(saved.id)?.id)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun inMemoryApprovalTakeToleratesMalformedId() {
        val store = InMemoryPendingApprovalStore()
        store.save(held())
        assertNull(store.take("not-an-approval-id"))
        assertNull(store.take(""))
        assertEquals(1, store.list().size)
    }

    // --- Seam 4: Room approval take is a no-op on malformed ids. ---

    private class FakeApprovalDao : PendingApprovalDao {
        val rows = mutableListOf<PendingApprovalRow>()
        private var nextPid = 0L
        override fun insert(row: PendingApprovalRow): Long {
            nextPid += 1
            rows += row.copy(pid = nextPid)
            return nextPid
        }
        override fun findById(pid: Long): PendingApprovalRow? = rows.firstOrNull { it.pid == pid }
        override fun listAll(): List<PendingApprovalRow> = rows.toList()
        override fun deleteById(pid: Long): Int = if (rows.removeAll { it.pid == pid }) 1 else 0
    }

    @Test
    fun roomApprovalTakeToleratesMalformedId() {
        val store = RoomPendingApprovalStore(FakeApprovalDao())
        val saved = store.save(held())
        assertNull(store.take("not-an-approval-id"))
        assertNull(store.take(""))
        assertEquals(saved.id, store.list().single().id)
    }
}
