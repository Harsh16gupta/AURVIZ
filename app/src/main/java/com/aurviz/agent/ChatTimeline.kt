package com.aurviz.agent

// T5: timeline = Plan in, rows out. Pure decision logic, JVM-testable.
// The chat screen draws these rows later; it adds no decisions of its own.

// Plan (GLOSSARY): the sequence of Tool calls the agent loop actually
// made to satisfy one command, recorded with their results. Moved here
// from the deleted up-front planner (ticket 43/NG-3): the draft never
// exists up front, so a Plan is only ever built from executed steps
// (AgentLoopResult.toTimeline) or the calls made so far (loopTimeline).
data class Plan(val steps: List<ToolCall>)

// What one row looks like on screen.
enum class StepState {
    READY,
    RUNNING,
    DONE,
    FAILED,
}

data class TimelineStep(
    val title: String,
    val state: StepState,
    val error: String? = null,
)

// Build the rows for one Plan run.
// doneCount = how many tools already finished. error = failure text, if any.
fun buildTimeline(plan: Plan, doneCount: Int, error: String?): List<TimelineStep> {
    val rows = mutableListOf<TimelineStep>()
    rows += TimelineStep("Heard command", StepState.DONE)
    rows += TimelineStep("Planned ${plan.steps.size} steps", StepState.DONE)
    val total = plan.steps.size
    for ((index, step) in plan.steps.withIndex()) {
        val title = "Tool ${index + 1} of $total: ${step.name}"
        val state =
            when {
                error != null && index == doneCount -> StepState.FAILED
                index < doneCount -> StepState.DONE
                index == doneCount -> StepState.RUNNING
                else -> StepState.READY
            }
        val stepError = if (error != null && index == doneCount) error else null
        rows += TimelineStep(title, state, stepError)
    }
    when {
        error != null -> rows += TimelineStep("Error: $error", StepState.FAILED)
        total == 0 -> rows += TimelineStep("Nothing to do — ask again", StepState.READY)
        doneCount >= total -> rows += TimelineStep("Done", StepState.DONE)
        else -> rows += TimelineStep("Working…", StepState.RUNNING)
    }
    return rows
}

// Retry the failed step without losing what already finished.
// Finds the FAILED tool row, marks it ready again, drops the Error row.
fun retryTimeline(steps: List<TimelineStep>): List<TimelineStep> {
    if (steps.none { it.state == StepState.FAILED && it.title.startsWith("Tool ") }) return steps
    val kept =
        steps
            .filterNot { it.title.startsWith("Error:") }
            .map { step ->
                if (step.state == StepState.FAILED) step.copy(state = StepState.RUNNING, error = null) else step
            }
    val last = kept.lastOrNull()?.title
    if (last == "Working…" || last == "Done") return kept
    return kept + TimelineStep("Working…", StepState.RUNNING)
}
