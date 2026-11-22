package com.aurviz.agent.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aurviz.agent.EmailDraft
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.TextField
import com.aurviz.agent.ui.theme.Theme

// The Confirmation gate for the sendEmail Tool: editable Vela TextFields
// pre-filled from the EmailDraft, then Approve & Send resolves the single
// gated path (executeGated as Approved) with the EDITED fields — the
// deterministic SendPolicy still screens before the transport. New
// recipients carry the unseen highlight.
@Composable
fun EmailConfirmationSheet(
    draft: EmailDraft?,
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
            var to by remember(draft) { mutableStateOf(draft.to.joinToString(", ")) }
            var subject by remember(draft) { mutableStateOf(draft.subject) }
            var body by remember(draft) { mutableStateOf(draft.body) }
            Section("Send this email?") {
                Text(
                    "Review and edit before approving — nothing sends until you tap Approve & Send.",
                    color = Theme.colors.onBackgroundMuted,
                )
                if (draft.to.any { it.trim().lowercase() in unseen.map { u -> u.trim().lowercase() }.toSet() }) {
                    Text("New recipient — never emailed before.", color = Theme.colors.destructive)
                }
                TextField(value = to, onValueChange = { to = it }, label = "To")
                TextField(value = subject, onValueChange = { subject = it }, label = "Subject")
                TextField(value = body, onValueChange = { body = it }, label = "Message", minLines = 4)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                Button(
                    onClick = { onApprove(to, subject, body) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Approve & Send")
                }
                Button(
                    onClick = onReject,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Reject")
                }
            }
        }
    }
}
