package com.aurviz.agent.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.EmptyState
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.IconButton
import com.aurviz.agent.ui.components.Page
import com.aurviz.agent.ui.components.SearchField
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.TextField
import com.aurviz.agent.ui.theme.Brain
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.Trash

// What the Agent remembers across restarts. Forgetting is preview-gated:
// the sheet lists what matches before anything is deleted.
@Composable
fun MemoryScreen(
    state: AgentUiState,
    onRemember: (String) -> Unit,
    onForget: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    val shown = if (query.isBlank()) {
        state.memories
    } else {
        state.memories.filter {
            it.text.contains(query.trim(), ignoreCase = true)
        }
    }

    Page(title = "Memory", onBack = onBack, modifier = modifier) {
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search remembered facts",
        )
        Section("Remembered") {
            if (shown.isEmpty()) {
                EmptyState(
                    Icons.Brain,
                    title = "Nothing remembered yet",
                    message = "Try: remember my boss is Priya",
                )
            } else {
                shown.forEach { memory ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space * 4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "• ${memory.text} (${memory.source})",
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onForget(memory.id) },
                            contentDescription = "Forget ${memory.text}",
                        ) {
                            Icon(
                                Icons.Trash,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Theme.colors.destructive,
                            )
                        }
                    }
                }
            }
        }
        Section("Add a fact") {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                label = "e.g. my boss is Priya",
            )
            Button(
                onClick = {
                    onRemember(draft)
                    draft = ""
                },
            ) {
                Text("Remember")
            }
        }
    }
}
