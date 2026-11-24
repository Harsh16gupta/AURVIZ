package com.aurviz.agent

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #116 (Memory Upgrade 4/4): opt-in Mem0 sync adapter.
// Room stays primary and OFF by default; SyncingMemoryStore mirrors
// best-effort to Mem0 without blocking local reads/writes or offline mode.
class Mem0SyncTest {

    private fun vault() = TokenVault(Mem0FakePrefs())

    private class FakeMem0Client(
        private val createdId: String? = "27777b1c-ff8d-4779-bf45-158a80033367",
    ) : Mem0Client {
        val added = mutableListOf<Memory>()
        val updated = mutableListOf<Pair<String, Memory>>()
        val deletedRemoteIds = mutableListOf<String>()
        var addCalls = 0
        var updateCalls = 0
        var deleteCalls = 0

        override fun addMemory(memory: Memory): String? {
            addCalls++
            added += memory
            return createdId
        }

        override fun updateMemory(remoteId: String, memory: Memory) {
            updateCalls++
            updated += remoteId to memory
        }

        override fun deleteMemory(remoteId: String) {
            deleteCalls++
            deletedRemoteIds += remoteId
        }
    }

    private class ThrowingMem0Client : Mem0Client {
        override fun addMemory(memory: Memory): String? = throw RuntimeException("network unreachable")
        override fun updateMemory(remoteId: String, memory: Memory) = throw RuntimeException("network unreachable")
        override fun deleteMemory(remoteId: String) = throw RuntimeException("network unreachable")
    }

    @Test
    fun mem0ConfigIsDisabledByDefaultInTokenVault() {
        val config = vault().loadMem0SyncConfig()
        assertTrue(!config.enabled)
        assertTrue(!config.isActive)
        assertEquals(DEFAULT_MEM0_BASE_URL, config.baseUrl)
        assertEquals("", config.apiKey)
        assertNull(mem0ClientFromConfig(config))
    }

    @Test
    fun mem0ConfigAndRemoteIdMapRoundTripAndSurviveClearGmailAndClearLlmKeys() {
        val vault = vault()
        vault.saveMem0SyncConfig(
            Mem0SyncConfig(
                enabled = true,
                baseUrl = "https://mem0.enterprise.local",
                apiKey = "m0-secret",
                userId = "u_42",
            ),
        )
        vault.saveMem0RemoteId("room_1", "27777b1c-ff8d-4779-bf45-158a80033367")
        vault.clearGmail()
        vault.clearLlmKeys()
        val reloaded = vault.loadMem0SyncConfig()
        assertEquals(
            Mem0SyncConfig(
                enabled = true,
                baseUrl = "https://mem0.enterprise.local",
                apiKey = "m0-secret",
                userId = "u_42",
            ),
            reloaded,
        )
        assertEquals("27777b1c-ff8d-4779-bf45-158a80033367", vault.loadMem0RemoteId("room_1"))
    }

    @Test
    fun syncingMemoryStoreWithNullRemotePerformsLocalOperationsOnly() {
        val local = InMemoryStore()
        val store = SyncingMemoryStore(local, remote = null, executor = Runnable::run)
        val saved = store.save("my boss is Priya", "you told me")
        assertEquals(listOf(saved), store.list())
        assertEquals(listOf(saved), store.searchFts("boss*"))
        store.markUsed(saved.id, nowMs = 1234L)
        assertEquals(1234L, store.list().single().lastUsed)
        assertEquals(1, store.list().single().useCount)
        store.delete(saved.id)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun syncingMemoryStoreMapsLocalIdToRemoteUuidAcrossSaveAndDelete() {
        val local = InMemoryStore()
        val fakeRemote = FakeMem0Client()
        val idStore = InMemoryMem0RemoteIdStore()
        val store = SyncingMemoryStore(local, remote = fakeRemote, idStore = idStore, executor = Runnable::run)
        val saved =
            store.save(
                "my boss is Priya",
                "you told me",
                originId = "in_7",
            )
        assertEquals("mem_1", saved.id)
        assertEquals("my boss is Priya", fakeRemote.added.single().text)
        assertEquals("in_7", fakeRemote.added.single().originId)
        store.delete("mem_1")
        assertEquals(listOf("27777b1c-ff8d-4779-bf45-158a80033367"), fakeRemote.deletedRemoteIds)
    }

    @Test
    fun syncingMemoryStoreReadsNeverTouchRemote() {
        val local = InMemoryStore()
        val fakeRemote = FakeMem0Client()
        var providerCalls = 0
        val store =
            SyncingMemoryStore(
                local = local,
                remoteProvider = {
                    providerCalls++
                    fakeRemote
                },
                executor = Runnable::run,
            )
        val saved = store.save("my boss is Priya", "you told me")
        // Reset counters so only the read path below is measured.
        providerCalls = 0
        fakeRemote.addCalls = 0
        fakeRemote.updateCalls = 0
        fakeRemote.deleteCalls = 0
        store.list()
        store.searchFts("boss*")
        store.markUsed(saved.id, nowMs = 999L)
        assertEquals(0, providerCalls)
        assertEquals(0, fakeRemote.addCalls)
        assertEquals(0, fakeRemote.updateCalls)
        assertEquals(0, fakeRemote.deleteCalls)
    }

    @Test
    fun syncingMemoryStoreSwallowsRemoteExceptionsWithoutBreakingLocalSaveOrDelete() {
        val local = InMemoryStore()
        val store = SyncingMemoryStore(local, remote = ThrowingMem0Client(), executor = Runnable::run)
        val saved = store.save("my boss is Priya", "you told me")
        assertEquals(listOf(saved), store.list())
        store.delete(saved.id)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun buildMem0AddAndUpdateBodiesAndParseCreatedIdMatchVerifiedMem0Schema() {
        val memory =
            Memory(
                "room_5",
                "my boss is Priya",
                "you told me",
                category = "work",
                importance = 9,
                createdAt = 1000L,
                originId = "in_1",
                originSnippet = "remember boss",
                originTimestamp = 1000L,
            )
        val addBody = buildMem0AddBody(memory, "user_1")
        assertEquals("user_1", addBody.getString("user_id"))
        assertEquals(false, addBody.getBoolean("infer"))
        assertEquals("my boss is Priya", addBody.getJSONArray("messages").getJSONObject(0).getString("content"))
        val addMetadata = addBody.getJSONObject("metadata")
        assertEquals("room_5", addMetadata.getString("local_id"))
        assertEquals("work", addMetadata.getString("category"))
        assertEquals("in_1", addMetadata.getString("origin_id"))
        assertEquals("remember boss", addMetadata.getString("origin_snippet"))
        assertEquals(1000L, addMetadata.getLong("origin_timestamp"))

        val updateBody = buildMem0UpdateBody(memory)
        assertEquals("my boss is Priya", updateBody.getString("text"))
        assertEquals("room_5", updateBody.getJSONObject("metadata").getString("local_id"))

        val createdId =
            parseMem0CreatedId(
                """{"message":"Memories stored successfully","status":"SUCCEEDED","event_id":"6a40f23c-dbc5-41f9-9df4-81fbd007b51c","results":[{"id":"27777b1c-ff8d-4779-bf45-158a80033367","data":{"memory":"my boss is Priya"},"event":"ADD"}]}""",
            )
        assertEquals("27777b1c-ff8d-4779-bf45-158a80033367", createdId)
    }

    @Test
    fun httpMem0ClientResolvesCloudVsSelfHostedEndpointsAndAuthHeaders() {
        val cloud = HttpMem0Client(baseUrl = "https://api.mem0.ai", apiKey = "m0-cloud-key")
        assertEquals("https://api.mem0.ai/v1/memories/", cloud.addEndpointUrl())
        assertEquals("https://api.mem0.ai/v1/memories/27777b1c/", cloud.itemEndpointUrl("27777b1c"))
        assertEquals("Token m0-cloud-key", cloud.authHeaders()["Authorization"])

        val selfHosted = HttpMem0Client(baseUrl = "https://mem0.corp.internal:8000/", apiKey = "m0sk_selfhosted")
        assertEquals("https://mem0.corp.internal:8000/memories", selfHosted.addEndpointUrl())
        assertEquals("https://mem0.corp.internal:8000/memories/27777b1c", selfHosted.itemEndpointUrl("27777b1c"))
        assertEquals("m0sk_selfhosted", selfHosted.authHeaders()["X-API-Key"])
        assertEquals("Bearer m0sk_selfhosted", selfHosted.authHeaders()["Authorization"])
    }
}

// In-memory SharedPreferences: same interface production's encrypted store
// implements, so the vault logic under test is identical.
private class Mem0FakePrefs : SharedPreferences {
    private val data = mutableMapOf<String, String?>()
    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private var clearAll = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = null
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearAll) {
                data.clear()
                clearAll = false
            }
            data.putAll(pending)
            pending.clear()
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
    }
    override fun getString(key: String, defValue: String?): String? =
        if (data.containsKey(key)) data[key] else defValue
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
