package com.aurviz.agent.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurviz.agent.EmailDraft
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.TextField
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.PencilSimple
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

// Ticket #91: the Confirmation gate for the sendEmail Tool, styled as a
// Proton Mail hairline field stack: To / From / Subject / Body in a 1dp
// bordered container with 1dp internal dividers. Read-only by default
// with an inline Edit toggle; 48dp Electric Blue Confirm send + outline
// Reject. Scrim-cancel maps to reject, so nothing sends unreviewed.
// GLOSSARY: Confirmation, Tool, Memory (via unseen-recipient receipt).
@Composable
fun EmailConfirmationSheet(
    draft: EmailDraft?,
    from: String? = null,
    unseen: List<String> = emptyList(),
    onApprove: (to: String, subject: String, body: String) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        visible = draft != null,
        onDismiss = onReject,
        title = "Confirmation",
        modifier = modifier,
    ) {
        if (draft != null) {
            // Keyed on the draft so each new preview resets the editors.
            var editing by remember(draft) { mutableStateOf(false) }
            var to by remember(draft) { mutableStateOf(draft.to.joinToString(", ")) }
            var subject by remember(draft) { mutableStateOf(draft.subject) }
            var body by remember(draft) { mutableStateOf(draft.body) }
            Section("Send this email?") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space * 2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Review and edit before approving — nothing sends until you tap Confirm send.",
                        color = Theme.colors.onBackgroundMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { editing = !editing },
                        variant = ButtonVariant.Ghost,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Space),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.PencilSimple,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(if (editing) "Done" else "Edit")
                        }
                    }
                }
                if (draft.to.any { it.trim().lowercase() in unseen.map { u -> u.trim().lowercase() }.toSet() }) {
                    Text("New recipient — never emailed before.", color = Theme.colors.destructive)
                }
                if (editing) {
                    TextField(value = to, onValueChange = { to = it }, label = "To")
                    TextField(value = subject, onValueChange = { subject = it }, label = "Subject")
                    TextField(value = body, onValueChange = { body = it }, label = "Message", minLines = 4)
                } else {
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(Theme.shapes.small)
                            .border(1.dp, Theme.colors.border, Theme.shapes.small),
                    ) {
                        HairlineRow(label = "To", value = to.ifBlank { "—" })
                        HairlineDivider()
                        HairlineRow(label = "From", value = from ?: "Gmail")
                        HairlineDivider()
                        HairlineRow(label = "Subject", value = subject.ifBlank { "—" })
                        HairlineDivider()
                        HairlineRow(label = "Body", value = body.ifBlank { "—" }, maxLines = 6)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                Button(
                    onClick = { onApprove(to, subject, body) },
                    enabled = to.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Text("Confirm send")
                }
                Button(
                    onClick = onReject,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Text("Reject")
                }
            }
        }
    }
}

@Composable
private fun HairlineRow(label: String, value: String, maxLines: Int = 2) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space * 4, vertical = Space * 3),
        horizontalArrangement = Arrangement.spacedBy(Space * 3),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = Theme.typography.caption,
            color = Theme.colors.onBackgroundMuted,
            modifier = Modifier.width(64.dp),
        )
        Text(
            value,
            style = Theme.typography.body,
            color = Theme.colors.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HairlineDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.colors.border))
}
