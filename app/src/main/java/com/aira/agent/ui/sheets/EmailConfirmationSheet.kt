package com.aira.agent.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aira.agent.EmailDraft
import com.aira.agent.ui.components.Button
import com.aira.agent.ui.components.ButtonVariant
import com.aira.agent.ui.components.CardDivider
import com.aira.agent.ui.components.Icon
import com.aira.agent.ui.components.ModalBottomSheet
import com.aira.agent.ui.components.Text
import com.aira.agent.ui.components.TextField
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.PencilSimple
import com.aira.agent.ui.theme.Theme

// Ticket #91: the Confirmation gate for the sendEmail Tool, styled as a
// Proton Mail hairline field stack: To / From / Subject / Body in a 0.5dp
// bordered inset container with 16dp-inset hairline dividers. Read-only by
// default with an inline tonal Edit toggle; 48dp Confirm send + Secondary
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Send this email?",
                        style = Theme.typography.label,
                        color = Theme.colors.onSurface,
                    )
                    Text(
                        "Review and edit before confirming — nothing sends until you tap Confirm send.",
                        style = Theme.typography.caption,
                        color = Theme.colors.onBackgroundMuted,
                    )
                }
                Box(
                    Modifier.heightIn(min = 36.dp)
                        .clip(Theme.shapes.fullyRounded)
                        .background(Theme.colors.surfaceVariant)
                        .clickable { editing = !editing }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.PencilSimple,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Theme.colors.onSurfaceVariant,
                        )
                        Text(
                            if (editing) "Done" else "Edit",
                            style = Theme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = Theme.colors.onSurfaceVariant,
                        )
                    }
                }
            }
            if (draft.to.any { it.trim().lowercase() in unseen.map { u -> u.trim().lowercase() }.toSet() }) {
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Theme.colors.warning.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "New recipient — never emailed before.",
                        style = Theme.typography.caption.copy(fontWeight = FontWeight.Medium),
                        color = Theme.colors.warning,
                    )
                }
            }
            if (editing) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextField(value = to, onValueChange = { to = it }, label = "To")
                    TextField(value = subject, onValueChange = { subject = it }, label = "Subject")
                    TextField(value = body, onValueChange = { body = it }, label = "Message", minLines = 4)
                }
            } else {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Theme.colors.inset)
                        .border(0.5.dp, Theme.colors.edge, RoundedCornerShape(16.dp)),
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
    CardDivider(inset = 16.dp)
}
