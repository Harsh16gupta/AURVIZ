package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.CLOSE_SESSIONS_DESCRIPTION
import com.aira.agent.ui.GROUP_PREVIOUS_7_DAYS
import com.aira.agent.ui.GROUP_TODAY
import com.aira.agent.ui.InteractionRecord
import com.aira.agent.ui.NEW_SESSION_LABEL
import com.aira.agent.ui.NO_PAST_SESSIONS_LINE
import com.aira.agent.ui.PAST_INTERACTIONS_TITLE
import com.aira.agent.ui.groupPastInteractions
import com.aira.agent.ui.theme.CaretRight
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.Plus
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.X

// Ticket #92: the Sessions slide-over behind the Command Menu icon —
// persisted Past Interactions grouped by Today / Previous 7 Days with a
// New session button. Stock foundation only; ChatGPT drawer rhythm
// (title + close, primary action, grouped rows).
@Composable
fun SessionsDrawer(
    visible: Boolean,
    interactions: List<InteractionRecord>,
    nowMs: Long,
    onDismiss: () -> Unit,
    onNewSession: () -> Unit,
    onSelectPast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = groupPastInteractions(interactions, nowMs)
    SideDrawer(visible = visible, onDismiss = onDismiss, modifier = modifier) {
        Column(Modifier.fillMaxHeight().padding(horizontal = Space * 4)) {
            Row(
                Modifier
                    .height(56.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(PAST_INTERACTIONS_TITLE, style = Theme.typography.title)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, contentDescription = CLOSE_SESSIONS_DESCRIPTION) {
                    Icon(
                        Icons.X,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Button(
                onClick = onNewSession,
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Secondary,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Plus,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(NEW_SESSION_LABEL)
                }
            }

            Spacer(Modifier.height(Space * 2))

            if (groups.today.isEmpty() && groups.previous7Days.isEmpty()) {
                Text(
                    NO_PAST_SESSIONS_LINE,
                    style = Theme.typography.body,
                    color = Theme.colors.onBackgroundMuted,
                    modifier = Modifier.padding(vertical = Space * 4),
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    if (groups.today.isNotEmpty()) {
                        item(key = "group-today") { GroupHeader(GROUP_TODAY) }
                        items(groups.today, key = { it.id }) { record ->
                            PastRow(record = record, onSelectPast = onSelectPast)
                        }
                    }
                    if (groups.previous7Days.isNotEmpty()) {
                        item(key = "group-previous-7-days") { GroupHeader(GROUP_PREVIOUS_7_DAYS) }
                        items(groups.previous7Days, key = { it.id }) { record ->
                            PastRow(record = record, onSelectPast = onSelectPast)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        label,
        style = Theme.typography.caption,
        color = Theme.colors.onBackgroundMuted,
        modifier = Modifier.padding(top = Space * 3, bottom = Space),
    )
}

@Composable
private fun PastRow(record: InteractionRecord, onSelectPast: (String) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    onClick = { onSelectPast(record.command) },
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.foundation.LocalIndication.current,
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                record.command,
                style = Theme.typography.label,
                color = Theme.colors.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.CaretRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Theme.colors.onBackgroundMuted,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Theme.colors.edge),
        )
    }
}
