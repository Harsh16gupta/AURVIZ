package com.aurviz.agent.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aurviz.agent.WebBridge
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.WebViewHolder
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.ChoiceTile
import com.aurviz.agent.ui.components.Markdown
import com.aurviz.agent.ui.components.Page
import com.aurviz.agent.ui.components.SearchField
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.ThinkingIndicator
import com.aurviz.agent.ui.theme.Theme

// Hosts the real android.webkit.WebView (used by WebViewBridge) inside an
// AndroidView, with the extracted title/text/links below in a toggleable
// panel. The WebView instance is retained in WebViewHolder and detached
// from its previous parent on re-attach, so it survives tab switches and
// `open` commands from Command never fail.
@Composable
fun BrowserScreen(
    state: AgentUiState,
    onOpenUrl: (String) -> Unit,
    onAttachBridge: (WebBridge) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var showExtracted by rememberSaveable { mutableStateOf(true) }

    Page(title = "Browser", onBack = onBack, modifier = modifier) {
        SearchField(
            value = url,
            onValueChange = { url = it },
            placeholder = "Paste a link, then Open",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
            Button(onClick = { onOpenUrl(url) }) {
                Text("Open")
            }
            if (state.webLoading) {
                ThinkingIndicator(thinking = true, seconds = 0, thoughts = "")
            }
        }
        if (state.webError != null) {
            Text(state.webError, color = Theme.colors.destructive)
        }
        AndroidView(
            factory = { context ->
                val wv = WebViewHolder.obtain(context)
                (wv.parent as? ViewGroup)?.removeView(wv)
                onAttachBridge(WebViewHolder.bridge(wv))
                wv
            },
            modifier = Modifier.fillMaxWidth().height(340.dp).clip(Theme.shapes.medium),
        )
        Section("Extracted") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                ChoiceTile(
                    selected = showExtracted,
                    onClick = { showExtracted = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Extracted", style = Theme.typography.label)
                }
                ChoiceTile(
                    selected = !showExtracted,
                    onClick = { showExtracted = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Page only", style = Theme.typography.label)
                }
            }
            if (showExtracted) {
                val page = state.webPage
                if (page == null) {
                    Text(
                        "Nothing opened yet — try: open example.com",
                        color = Theme.colors.onBackgroundMuted,
                    )
                } else {
                    Text(page.title.ifBlank { page.url }, style = Theme.typography.title)
                    Markdown(page.text.split("\n").take(40).joinToString("\n"))
                    if (page.links.isNotEmpty()) {
                        Text(
                            "${page.links.size} links",
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(Space)) {
                            page.links.take(10).forEach { link ->
                                Button(
                                    onClick = { onOpenUrl(link) },
                                    variant = ButtonVariant.Secondary,
                                ) {
                                    Text(link, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
