package com.aurviz.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurviz.agent.Memory
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.EmptyState
import com.aurviz.agent.ui.components.HapticKind
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.IconButton
import com.aurviz.agent.ui.components.LocalHaptics
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.Page
import com.aurviz.agent.ui.components.SearchField
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.TextField
import com.aurviz.agent.ui.components.Toast
import com.aurviz.agent.ui.theme.Brain
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.PencilSimple
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.Trash
import kotlinx.coroutines.delay

// What the Agent remembers across restarts. Forgetting is preview-gated:
// the sheet lists what matches before anything is deleted.
@Composable
fun MemoryScreen(
    state: AgentUiState,
    onRemember: (String) -> Unit,
    onForget: (String) -> Unit,
    onBack: () -> Unit,
    onUpdateMemory: (Memory, String) -> Unit = { _, _ -> },
    onRestoreMemory: (Memory) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var facet by rememberSaveable { mutableStateOf("All") }
    var recentlyDeleted by remember { mutableStateOf<Memory?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var addDraft by rememberSaveable { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<Memory?>(null) }
    var editDraft by remember { mutableStateOf("") }
    val haptics = LocalHaptics.current

    val facets = remember(state.memories) {
        (state.memories.map { it.category } + state.memories.map { it.source })
            .filter { it.isNotBlank() }
            .distinct()
    }
    val shown = state.memories.filter { memory ->
        (facet == "All" || memory.category == facet || memory.source == facet) &&
            (query.isBlank() || memory.text.contains(query.trim(), ignoreCase = true))
    }

    LaunchedEffect(recentlyDeleted) {
        if (recentlyDeleted != null) {
            delay(4000L)
            recentlyDeleted = null
        }
    }

    Page(
        title = "Memory",
        onBack = onBack,
        modifier = modifier,
        overlay = {
            val deleted = recentlyDeleted
            if (deleted != null) {
                Box(
                    Modifier.fillMaxSize().padding(bottom = Space * 6),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier.clickable(
                            onClick = {
                                onRestoreMemory(deleted)
                                recentlyDeleted = null
                            },
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ),
                    ) {
                        Toast(visible = true, message = "Memory deleted · Tap to Undo")
                    }
                }
            }
        },
    ) {
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search remembered facts",
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space * 2),
        ) {
            (listOf("All") + facets).forEach { pill ->
                val selected = facet == pill
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) Theme.colors.primary else Theme.colors.surfaceVariant,
                        )
                        .clickable {
                            haptics?.play(HapticKind.Selection)
                            facet = pill
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        pill,
                        style = Theme.typography.caption,
                        color = if (selected) Theme.colors.onPrimary else Theme.colors.onBackgroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Section("Remembered") {
            if (shown.isEmpty()) {
                EmptyState(
                    Icons.Brain,
                    title = "Nothing remembered yet",
                    message = "Try: remember my boss is Priya",
                )
            } else {
                shown.forEach { memory ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Theme.colors.card)
                            .border(0.5.dp, Theme.colors.edge, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(Space),
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(Space),
                            ) {
                                Text(
                                    memory.text,
                                    style = Theme.typography.body,
                                    color = Theme.colors.onBackground,
                                )
                                val meta = buildString {
                                    append(memory.category)
                                    append(" · ")
                                    append(memory.source)
                                    if (memory.useCount > 0) append(" · Used ${memory.useCount}×")
                                }
                                Text(
                                    meta,
                                    style = Theme.typography.code,
                                    color = Theme.colors.onBackgroundMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = {
                                    editTarget = memory
                                    editDraft = memory.text
                                },
                                contentDescription = "Edit Memory",
                            ) {
                                Icon(
                                    Icons.PencilSimple,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    onForget(memory.id)
                                    recentlyDeleted = memory
                                },
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
        }
        Button(onClick = { showAdd = true }) {
            Text("+ Add Memory")
        }

        ModalBottomSheet(
            visible = showAdd,
            onDismiss = { showAdd = false },
            title = "Add Memory",
        ) {
            TextField(
                value = addDraft,
                onValueChange = { addDraft = it },
                label = "e.g. my boss is Priya",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                Button(
                    onClick = {
                        onRemember(addDraft)
                        addDraft = ""
                        showAdd = false
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save")
                }
                Button(
                    onClick = { showAdd = false },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
            }
        }

        ModalBottomSheet(
            visible = editTarget != null,
            onDismiss = { editTarget = null },
            title = "Edit Memory",
        ) {
            TextField(
                value = editDraft,
                onValueChange = { editDraft = it },
                label = "Memory text",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                Button(
                    onClick = {
                        editTarget?.let { onUpdateMemory(it, editDraft) }
                        editTarget = null
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save")
                }
                Button(
                    onClick = { editTarget = null },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}
