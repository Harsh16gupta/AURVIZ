package com.aira.agent

import java.net.URLEncoder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// ADR-0006: Opt-in Mem0 Sync Adapter (SyncingMemoryStore).
// OFF by default. Room (local) is always the source of truth for reads
// (list, searchFts, markUsed); writes and deletes commit to local Room first
// and mirror best-effort to Mem0 (cloud api.mem0.ai or self-hosted OSS server)
// without blocking the caller or failing offline commands.

interface Mem0Client {
    /** Creates a new memory on Mem0 and returns the remote Mem0 UUID (or null if unavailable). */
    fun addMemory(memory: Memory): String?

    /** Updates an existing memory on Mem0 by its remote Mem0 UUID. */
    fun updateMemory(remoteId: String, memory: Memory)

    /** Deletes a memory on Mem0 by its remote Mem0 UUID. */
    fun deleteMemory(remoteId: String)
}

interface Mem0RemoteIdStore {
    fun getRemoteId(localId: String): String?
    fun putRemoteId(localId: String, remoteId: String)
    fun removeRemoteId(localId: String)
}

class InMemoryMem0RemoteIdStore : Mem0RemoteIdStore {
    private val map = mutableMapOf<String, String>()

    @Synchronized
    override fun getRemoteId(localId: String): String? = map[localId]

    @Synchronized
    override fun putRemoteId(localId: String, remoteId: String) {
        if (localId.isNotBlank() && remoteId.isNotBlank()) {
            map[localId] = remoteId
        }
    }

    @Synchronized
    override fun removeRemoteId(localId: String) {
        map.remove(localId)
    }
}

class VaultMem0RemoteIdStore(private val vault: TokenVault) : Mem0RemoteIdStore {
    override fun getRemoteId(localId: String): String? = vault.loadMem0RemoteId(localId)
    override fun putRemoteId(localId: String, remoteId: String) = vault.saveMem0RemoteId(localId, remoteId)
    override fun removeRemoteId(localId: String) = vault.removeMem0RemoteId(localId)
}

fun isMem0PlatformCloudUrl(baseUrl: String): Boolean {
    val normalized = baseUrl.trim().lowercase()
    return normalized.contains("://api.mem0.ai")
}

fun buildMem0Metadata(memory: Memory): JSONObject {
    val metadata =
        JSONObject()
            .put("local_id", memory.id)
            .put("source", memory.source)
            .put("category", memory.category)
            .put("importance", memory.importance)
            .put("created_at", memory.createdAt)
    if (memory.originId != null) metadata.put("origin_id", memory.originId)
    if (memory.originSnippet != null) metadata.put("origin_snippet", memory.originSnippet)
    if (memory.originTimestamp != null) metadata.put("origin_timestamp", memory.originTimestamp)
    return metadata
}

/**
 * Builds the canonical Mem0 create payload:
 * - `messages`: `[{"role": "user", "content": memory.text}]`
 * - `user_id`: top-level user identifier
 * - `infer`: `false` (on-device `MemoryReconciler` has already extracted/reconciled the atomic fact)
 * - `metadata`: local Room id + ranking + provenance metadata
 */
fun buildMem0AddBody(memory: Memory, userId: String): JSONObject {
    val messages =
        JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", memory.text),
        )
    return JSONObject()
        .put("user_id", userId)
        .put("messages", messages)
        .put("infer", false)
        .put("metadata", buildMem0Metadata(memory))
}

/**
 * Builds the canonical Mem0 update payload (`PUT /v1/memories/{id}/` or `PUT /memories/{id}`):
 * - `text`: replacement memory text
 * - `metadata`: updated metadata dictionary
 */
fun buildMem0UpdateBody(memory: Memory): JSONObject =
    JSONObject()
        .put("text", memory.text)
        .put("metadata", buildMem0Metadata(memory))

/**
 * Extracts the remote Mem0 memory UUID from the add response JSON:
 * - Platform/OSS v1.1 envelope: `{"results":[{"id":"27777b1c-...","data":{"memory":"..."},"event":"ADD"}]}`
 * - Direct object fallback: `{"id":"27777b1c-..."}`
 */
fun parseMem0CreatedId(responseBody: String): String? {
    val trimmed = responseBody.trim()
    if (trimmed.isEmpty()) return null
    return try {
        val obj = JSONObject(trimmed)
        val results = obj.optJSONArray("results")
        if (results != null && results.length() > 0) {
            val first = results.optJSONObject(0)
            val id = first?.optString("id", "")?.trim().orEmpty()
            if (id.isNotEmpty()) return id
        }
        obj.optString("id", "").trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

private fun encodePathSegment(segment: String): String =
    URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")

class HttpMem0Client(
    private val baseUrl: String = DEFAULT_MEM0_BASE_URL,
    private val apiKey: String,
    private val userId: String = "aira_user",
    private val client: OkHttpClient = llmHttpClient(),
) : Mem0Client {

    internal fun addEndpointUrl(): String {
        val root = baseUrl.trim().trimEnd('/')
        return if (isMem0PlatformCloudUrl(root)) "$root/v1/memories/" else "$root/memories"
    }

    internal fun itemEndpointUrl(remoteId: String): String {
        val root = baseUrl.trim().trimEnd('/')
        val encoded = encodePathSegment(remoteId)
        return if (isMem0PlatformCloudUrl(root)) "$root/v1/memories/$encoded/" else "$root/memories/$encoded"
    }

    internal fun authHeaders(): Map<String, String> {
        val trimmedKey = apiKey.trim()
        val authValue =
            if (isMem0PlatformCloudUrl(baseUrl)) {
                "Token $trimmedKey"
            } else {
                "Bearer $trimmedKey"
            }
        return mapOf(
            "Authorization" to authValue,
            "X-API-Key" to trimmedKey,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )
    }

    private fun Request.Builder.applyHeaders(): Request.Builder {
        for ((k, v) in authHeaders()) {
            header(k, v)
        }
        return this
    }

    override fun addMemory(memory: Memory): String? {
        if (apiKey.isBlank() || baseUrl.isBlank()) return null
        val body = buildMem0AddBody(memory, userId).toString()
        val request =
            Request.Builder()
                .url(addEndpointUrl())
                .applyHeaders()
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) return null
            return parseMem0CreatedId(text)
        }
    }

    override fun updateMemory(remoteId: String, memory: Memory) {
        if (apiKey.isBlank() || baseUrl.isBlank() || remoteId.isBlank()) return
        val body = buildMem0UpdateBody(memory).toString()
        val request =
            Request.Builder()
                .url(itemEndpointUrl(remoteId))
                .applyHeaders()
                .put(body.toRequestBody("application/json".toMediaType()))
                .build()
        client.newCall(request).execute().close()
    }

    override fun deleteMemory(remoteId: String) {
        if (apiKey.isBlank() || baseUrl.isBlank() || remoteId.isBlank()) return
        val request =
            Request.Builder()
                .url(itemEndpointUrl(remoteId))
                .applyHeaders()
                .delete()
                .build()
        client.newCall(request).execute().close()
    }
}

class SyncingMemoryStore(
    private val local: MemoryStore,
    private val remoteProvider: () -> Mem0Client? = { null },
    private val idStore: Mem0RemoteIdStore = InMemoryMem0RemoteIdStore(),
    private val executor: Executor = Executors.newSingleThreadExecutor(),
) : MemoryStore {

    constructor(
        local: MemoryStore,
        remote: Mem0Client?,
        idStore: Mem0RemoteIdStore = InMemoryMem0RemoteIdStore(),
        executor: Executor = Executors.newSingleThreadExecutor(),
    ) : this(local = local, remoteProvider = { remote }, idStore = idStore, executor = executor)

    override fun save(
        text: String,
        source: String,
        category: String,
        importance: Int,
        createdAt: Long,
        lastUsed: Long,
        useCount: Int,
        originId: String?,
        originSnippet: String?,
        originTimestamp: Long?,
    ): Memory {
        val saved =
            local.save(
                text = text,
                source = source,
                category = category,
                importance = importance,
                createdAt = createdAt,
                lastUsed = lastUsed,
                useCount = useCount,
                originId = originId,
                originSnippet = originSnippet,
                originTimestamp = originTimestamp,
            )
        val client = try { remoteProvider() } catch (_: Exception) { null }
        if (client != null) {
            try {
                executor.execute {
                    try {
                        val existingRemoteId = idStore.getRemoteId(saved.id)
                        if (existingRemoteId != null) {
                            client.updateMemory(existingRemoteId, saved)
                        } else {
                            val createdId = client.addMemory(saved)
                            if (!createdId.isNullOrBlank()) {
                                idStore.putRemoteId(saved.id, createdId)
                            }
                        }
                    } catch (_: Exception) {
                        // Best-effort mirror: never crash or block offline mode.
                    }
                }
            } catch (_: Exception) {
            }
        }
        return saved
    }

    override fun list(): List<Memory> = local.list()

    override fun searchFts(ftsQuery: String): List<Memory> = local.searchFts(ftsQuery)

    override fun delete(id: String) {
        local.delete(id)
        val remoteId = idStore.getRemoteId(id) ?: id
        idStore.removeRemoteId(id)
        val client = try { remoteProvider() } catch (_: Exception) { null }
        if (client != null) {
            try {
                executor.execute {
                    try {
                        client.deleteMemory(remoteId)
                    } catch (_: Exception) {
                        // Best-effort mirror: never crash or block offline mode.
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun markUsed(id: String, nowMs: Long) {
        local.markUsed(id, nowMs)
    }
}

fun mem0ClientFromConfig(config: Mem0SyncConfig): Mem0Client? =
    if (config.isActive) {
        HttpMem0Client(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            userId = config.userId,
        )
    } else {
        null
    }

fun memoryStoreWithOptionalSync(
    local: MemoryStore,
    tokenVault: TokenVault,
    executor: Executor = Executors.newSingleThreadExecutor(),
): SyncingMemoryStore =
    SyncingMemoryStore(
        local = local,
        remoteProvider = { mem0ClientFromConfig(tokenVault.loadMem0SyncConfig()) },
        idStore = VaultMem0RemoteIdStore(tokenVault),
        executor = executor,
    )
