package com.aurviz.agent.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aurviz.agent.ui.VoiceUiState
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.VoiceOrb
import com.aurviz.agent.ui.theme.Theme

// A Voice Command is spoken input handled like a typed command. The orb is
// driven by VoiceUiState only — onRmsChanged never reaches the ViewModel —
// while live onPartialResults() text shows below it.
@Composable
fun VoiceCommandSheet(
    visible: Boolean,
    state: VoiceUiState,
    onDismiss: () -> Unit,
    onMicTap: () -> Unit,
    onUseTranscript: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Voice Command",
        modifier = modifier,
    ) {
        Section("Listen") {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space * 3),
            ) {
                VoiceOrb(state = state.orb)
                val heard = state.transcript.ifBlank { "Tap the mic and speak — typing always works too." }
                Text(heard, color = Theme.colors.onBackgroundMuted)
                if (state.error != null) {
                    Text(state.error, color = Theme.colors.destructive)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
            Button(
                onClick = onMicTap,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.listening) "Listening…" else "Tap to speak")
            }
            Button(
                onClick = onUseTranscript,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            ) {
                Text("Use as command")
            }
        }
    }
}
