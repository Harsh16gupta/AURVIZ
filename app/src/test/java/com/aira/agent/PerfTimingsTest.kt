package com.aira.agent

import com.aira.agent.ui.PerfTimings
import com.aira.agent.ui.recordRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 37 (submission proof): reproducible 10-run per-stage timing
// medians on the JVM. No network, no device: memory recall (recallMs
// stage), system-prompt shaping (planMs input stage), and registry arg
// validation + dispatch (toolMs stage). LLM/STT/first-token/end-to-end
// need keys + a device and are recorded as a human-pass method in
// docs/PERF_TIMINGS.md, never fabricated here.
class PerfTimingsTest {

    private fun seedStore(size: Int): InMemoryStore {
        val store = InMemoryStore()
        repeat(size) { i ->
            store.save(
                "memory fact number $i about boss Priya standup routine",
                source = if (i % 2 == 0) "you told me" else "auto-saved",
                category = "work",
                importance = i % 10,
                createdAt = 1_000L + i,
            )
        }
        return store
    }

    private fun medianMs(samples: List<Double>): Double = samples.sorted()[samples.size / 2]

    @Test
    fun tenRunStageMediansAreRecordedAndBounded() {
        val store = seedStore(500)
        val query = "who is my boss Priya standup?"

        // Warm up once so JIT settles before the measured runs.
        repeat(3) { recallFor(store, query) }

        val recallSamples = mutableListOf<Double>()
        repeat(10) {
            val t0 = System.nanoTime()
            val hits = recallFor(store, query)
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            recallSamples += ms
            assertTrue(hits.isNotEmpty())
        }

        val top5 = recallFor(store, query).take(5)
        val promptSamples = mutableListOf<Double>()
        repeat(10) {
            val t0 = System.nanoTime()
            val prompt = buildSystemPrompt(top5)
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            promptSamples += ms
            assertTrue(prompt.contains("[memories]"))
        }

        val registry = defaultToolRegistry(memoryStore = store)
        val validationSamples = mutableListOf<Double>()
        repeat(10) {
            val t0 = System.nanoTime()
            val result = registry.execute("search_memory", mapOf("query" to query, "max_results" to 5))
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            validationSamples += ms
            assertTrue(result is ToolResult.Success)
        }

        val recallMed = medianMs(recallSamples)
        val promptMed = medianMs(promptSamples)
        val validationMed = medianMs(validationSamples)

        // Before/after optimization: full 500-memory prompt section vs
        // top-5 trimmed section (the shipped path trims via max_results).
        val all = store.list()
        val fullSamples = mutableListOf<Double>()
        repeat(10) {
            val t0 = System.nanoTime()
            val s = renderMemorySection(all)
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            fullSamples += ms
            assertTrue(s.length > 10_000)
        }
        val trimmedSamples = mutableListOf<Double>()
        repeat(10) {
            val t0 = System.nanoTime()
            val s = renderMemorySection(all.take(5))
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            trimmedSamples += ms
            assertTrue(s.length < 1_000)
        }
        val fullMed = medianMs(fullSamples)
        val trimmedMed = medianMs(trimmedSamples)

        println("PERF recall_ms_10runs=" + recallSamples.joinToString(",") { "%.3f".format(it) })
        println("PERF recall_median_ms=%.3f".format(recallMed))
        println("PERF prompt_ms_10runs=" + promptSamples.joinToString(",") { "%.3f".format(it) })
        println("PERF prompt_median_ms=%.3f".format(promptMed))
        println("PERF registry_search_memory_ms_10runs=" + validationSamples.joinToString(",") { "%.3f".format(it) })
        println("PERF registry_median_ms=%.3f".format(validationMed))
        println("PERF full500_render_ms_10runs=" + fullSamples.joinToString(",") { "%.3f".format(it) })
        println("PERF full500_median_ms=%.3f".format(fullMed))
        println("PERF top5_render_ms_10runs=" + trimmedSamples.joinToString(",") { "%.3f".format(it) })
        println("PERF top5_median_ms=%.3f".format(trimmedMed))

        // Generous bounds: JVM CI machines vary; these catch regressions
        // (e.g. accidental O(n^2) ranking), not device absolute targets.
        assertTrue("recall median too slow: $recallMed ms", recallMed < 250.0)
        assertTrue("prompt median too slow: $promptMed ms", promptMed < 50.0)
        assertTrue("registry median too slow: $validationMed ms", validationMed < 250.0)
        assertTrue("trimmed prompt must be smaller than full prompt", trimmedMed <= fullMed + 5.0)
    }

    // Ticket #90: rolling averages roll forward and last-run fields survive
    // runs that do not measure their stage.
    @Test
    fun recordRunRollsAveragesAndPreservesLastRunFields() {
        var timings = PerfTimings()
        timings = timings.recordRun(planMs = 100, toolMs = 50, recallMs = 20, success = true)
        assertEquals(100L, timings.planMs)
        assertEquals(50L, timings.toolMs)
        assertEquals(20L, timings.recallMs)
        assertEquals(100L, timings.planAvgMs)
        assertEquals(50L, timings.toolAvgMs)
        assertEquals(20L, timings.recallAvgMs)
        assertEquals(1, timings.runs)
        assertEquals(1.0, timings.successRate, 1e-9)

        timings = timings.recordRun(planMs = 300, success = false)
        assertEquals(300L, timings.planMs)
        assertEquals(50L, timings.toolMs)
        assertEquals(20L, timings.recallMs)
        assertEquals(200L, timings.planAvgMs)
        assertEquals(50L, timings.toolAvgMs)
        assertEquals(20L, timings.recallAvgMs)
        assertEquals(2, timings.runs)
        assertEquals(0.5, timings.successRate, 1e-9)
    }

    @Test
    fun recordRunKeepsDefaultsUntilMeasured() {
        val timings = PerfTimings().recordRun(success = true)
        assertEquals(null, timings.planMs)
        assertEquals(null, timings.planAvgMs)
        assertEquals(1, timings.runs)
        assertEquals(1.0, timings.successRate, 1e-9)
    }
}
