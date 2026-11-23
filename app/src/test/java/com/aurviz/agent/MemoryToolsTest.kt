package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 74 (part of #71): Memory adapter pin. The adapter owns the two
// Memory Tool declarations and registers them into the validate-and-gate
// kernel; behavior is exercised through the kernel seam (execute,
// schema view), never by file location. Fake store only.
class MemoryToolsTest {

    private fun kernelWithMemory(store: MemoryStore?): ToolRegistry =
        ToolRegistry().apply {
            for (tool in memoryTools(store)) register(tool)
        }

    @Test
    fun adapterRegistersSaveAndSearchIntoTheKernel() {
        val kernel = kernelWithMemory(InMemoryStore())
        assertEquals(setOf("save_memory", "search_memory"), kernel.names())
    }

    @Test
    fun bothMemoryToolsStayUngatedLowRisk() {
        val kernel = kernelWithMemory(InMemoryStore())
        val save = kernel.find("save_memory")!!
        assertEquals(RiskLevel.LOW, save.riskLevel)
        assertFalse(save.requiresConfirmation)
        val search = kernel.find("search_memory")!!
        assertEquals(RiskLevel.LOW, search.riskLevel)
        assertFalse(search.requiresConfirmation)
    }

    @Test
    fun saveAndRankedRecallBehaveThroughTheKernelSeam() {
        val kernel = kernelWithMemory(InMemoryStore())
        val first = kernel.execute("save_memory", mapOf("text" to "my boss is Priya"))
        assertTrue(first is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val saved = (first as ToolResult.Success).data as Map<String, Any?>
        assertEquals("my boss is Priya", saved["text"])
        assertEquals("you told me", saved["source"])

        kernel.execute("save_memory", mapOf("text" to "my boss likes tea", "source" to "auto-saved"))
        val found = kernel.execute("search_memory", mapOf("query" to "who is my boss Priya?"))
        assertTrue(found is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val hits = (found as ToolResult.Success).data as List<Map<String, Any?>>
        assertEquals(2, hits.size)
        assertEquals("my boss is Priya", hits[0]["text"])
    }

    @Test
    fun blankSourceDefaultsToYouToldMe() {
        val kernel = kernelWithMemory(InMemoryStore())
        val result = kernel.execute("save_memory", mapOf("text" to "my boss is Priya", "source" to "  "))
        assertTrue(result is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val saved = (result as ToolResult.Success).data as Map<String, Any?>
        assertEquals("you told me", saved["source"])
    }

    @Test
    fun maxResultsTrimsRankedRecall() {
        val store = InMemoryStore()
        val kernel = kernelWithMemory(store)
        kernel.execute("save_memory", mapOf("text" to "my boss is Priya"))
        kernel.execute("save_memory", mapOf("text" to "my boss likes tea"))
        val found = kernel.execute("search_memory", mapOf("query" to "boss", "max_results" to 1))
        assertTrue(found is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val hits = (found as ToolResult.Success).data as List<Map<String, Any?>>
        assertEquals(1, hits.size)
    }

    @Test
    fun missingStoreStillFailsClosed() {
        val kernel = kernelWithMemory(null)
        val saved = kernel.execute("save_memory", mapOf("text" to "my boss is Priya"))
        assertTrue(saved is ToolResult.Failure)
        assertFalse((saved as ToolResult.Failure).retryable)
        val found = kernel.execute("search_memory", mapOf("query" to "boss"))
        assertTrue(found is ToolResult.Failure)
        assertFalse((found as ToolResult.Failure).retryable)
    }

    @Test
    fun schemaViewAdvertisesBothTools() {
        val names =
            kernelWithMemory(InMemoryStore()).toolSchemas().let { schemas ->
                (0 until schemas.length())
                    .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
                    .toSet()
            }
        assertEquals(setOf("save_memory", "search_memory"), names)
    }
}
