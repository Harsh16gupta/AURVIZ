package com.aira.agent

// Human labels for Tool names (GLOSSARY.md: UI copy never shows raw
// snake_case Tool IDs). Single-word Tools get an explicit override so the
// fallback title-casing never leaks an implementation name into UI.
private val ToolLabelOverrides: Map<String, String> = mapOf(
    "send_email" to "Send email",
    "search_emails" to "Search emails",
    "read_email" to "Read email",
    "save_memory" to "Save Memory",
    "search_memory" to "Search Memory",
    "create_schedule" to "Create schedule",
    "list_schedules" to "List schedules",
    "cancel_schedule" to "Cancel schedule",
    "open_url" to "Open page",
    "get_page_text" to "Read page",
    "click" to "Select item",
    "scroll" to "Scroll page",
    "extract" to "Extract details",
)

// Maps a registry Tool name to its UI label. Unknown names fall back to
// sentence case so no raw ID ever reaches the screen.
fun toolLabel(toolName: String): String {
    ToolLabelOverrides[toolName]?.let { return it }
    return toolName.split("_")
        .filter { it.isNotEmpty() }
        .mapIndexed { index, word ->
            if (index == 0) word.replaceFirstChar { it.uppercase() } else word.lowercase()
        }
        .joinToString(" ")
        .ifBlank { toolName }
}
