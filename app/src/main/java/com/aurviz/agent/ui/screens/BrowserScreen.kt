package com.aurviz.agent.ui.screens

import android.view.ViewGroup
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurviz.agent.WebBridge
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.WebViewHolder
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.ChoiceTile
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.IconButton
import com.aurviz.agent.ui.components.Markdown
import com.aurviz.agent.ui.components.ScreenScaffold
import com.aurviz.agent.ui.components.SearchField
import com.aurviz.agent.ui.components.StatusBanner
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.theme.ArrowClockwise
import com.aurviz.agent.ui.theme.CaretLeft
import com.aurviz.agent.ui.theme.CaretRight
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Theme

// Hosts the real android.webkit.WebView (used by WebViewBridge) inside an
// AndroidView, with browser controls, a load progress bar, and a collapsible
// inspector tray for extracted text and links. The WebView instance is
// retained in WebViewHolder and detached from its previous parent on
// re-attach, so it survives tab switches and `open` commands from Command
// never fail.
//
// Ticket 41/NG-5: the address bar and extracted-link buttons forward raw
// text to onOpenUrl (AgentViewModel.openWeb), which routes through the
// shared allowlist (routeBrowserUrl/isAllowedWebUrl) and refuses non-web
// addresses with an explanation — the Browser tab holds no second policy.
@Composable
fun BrowserScreen(
    state: AgentUiState,
    onOpenUrl: (String) -> Unit,
    onAttachBridge: (WebBridge) -> Unit,
    onBack: () -> Unit = {},
    onSaveMemory: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var panel by rememberSaveable { mutableStateOf("Page") }
    val progress by WebViewHolder.progress.collectAsStateWithLifecycle()
    val canGoBack by WebViewHolder.canGoBack.collectAsStateWithLifecycle()
    val canGoForward by WebViewHolder.canGoForward.collectAsStateWithLifecycle()
    val showProgress = progress in 1..99 || state.webLoading

    ScreenScaffold(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    // Top inset comes from ScreenScaffold exactly once.
                    .padding(horizontal = Space * 2, vertical = Space * 2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space),
            ) {
                IconButton(
                    onClick = { WebViewHolder.goBack() },
                    contentDescription = "Back",
                    enabled = canGoBack,
                ) {
                    Icon(Icons.CaretLeft, contentDescription = null, modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick = { WebViewHolder.goForward() },
                    contentDescription = "Forward",
                    enabled = canGoForward,
                ) {
                    Icon(Icons.CaretRight, contentDescription = null, modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick = { WebViewHolder.reload() },
                    contentDescription = "Reload",
                ) {
                    Icon(Icons.ArrowClockwise, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                SearchField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "Paste a link, then Open",
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onOpenUrl(url) }) {
                    Text("Go")
                }
            }

            if (showProgress) {
                val transition = rememberInfiniteTransition(label = "WebProgress")
                val pulse by transition.animateFloat(
                    0.35f,
                    1f,
                    infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "Pulse",
                )
                val fraction = if (progress in 1..99) progress / 100f else 1f
                val alpha = if (progress in 1..99) 1f else pulse
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    Box(
                        Modifier.fillMaxWidth(fraction)
                            .height(2.dp)
                            .background(Theme.colors.primary.copy(alpha = alpha)),
                    )
                }
            }

            if (state.webError != null) {
                StatusBanner(
                    message = state.webError,
                    actionLabel = "Retry",
                    onAction = { WebViewHolder.reload() },
                    modifier = Modifier.padding(horizontal = Space * 4, vertical = Space),
                )
            }

            AndroidView(
                factory = { context ->
                    val wv = WebViewHolder.obtain(context)
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    onAttachBridge(WebViewHolder.bridge(wv))
                    wv
                },
                modifier = Modifier.fillMaxWidth()
                    .weight(if (panel == "Page") 1f else 0.55f),
            )

            Column(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Theme.colors.panel),
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .height(0.5.dp)
                        .background(Theme.colors.edge),
                )
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = Space * 4, vertical = Space * 2),
                    horizontalArrangement = Arrangement.spacedBy(Space * 2),
                ) {
                    val page = state.webPage
                    ChoiceTile(
                        selected = panel == "Page",
                        onClick = { panel = "Page" },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Page", style = Theme.typography.label)
                    }
                    ChoiceTile(
                        selected = panel == "Extracted",
                        onClick = { panel = "Extracted" },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Extracted", style = Theme.typography.label)
                    }
                    ChoiceTile(
                        selected = panel == "Links",
                        onClick = { panel = "Links" },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Links (${page?.links?.size ?: 0})",
                            style = Theme.typography.label,
                        )
                    }
                }

                if (panel != "Page") {
                    Column(
                        Modifier.fillMaxWidth()
                            .weight(0.45f)
                            .padding(horizontal = Space * 4)
                            .padding(bottom = Space * 4),
                        verticalArrangement = Arrangement.spacedBy(Space * 2),
                    ) {
                        val page = state.webPage
                        if (page == null) {
                            Text(
                                "Nothing opened yet — try: open example.com",
                                color = Theme.colors.onBackgroundMuted,
                            )
                        } else if (panel == "Extracted") {
                            Text(
                                page.title.ifBlank { page.url },
                                style = Theme.typography.title,
                                color = Theme.colors.onBackground,
                            )
                            Box(
                                Modifier.fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Markdown(page.text)
                            }
                            Button(
                                onClick = {
                                    onSaveMemory("Web note (${page.title}): ${page.text.take(220)}")
                                },
                                variant = ButtonVariant.Secondary,
                            ) {
                                Text("Save to Memory")
                            }
                        } else {
                            if (page.links.isEmpty()) {
                                Text(
                                    "No links on this page.",
                                    color = Theme.colors.onBackgroundMuted,
                                )
                            } else {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(Space),
                                ) {
                                    page.links.forEach { link ->
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
    }
}
