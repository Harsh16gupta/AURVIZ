package com.aira.agent.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aira.agent.WebBridge
import com.aira.agent.browserChromeState
import com.aira.agent.browserSaveNote
import com.aira.agent.toolLabel
import com.aira.agent.ui.AgentUiState
import com.aira.agent.ui.WebViewHolder
import com.aira.agent.ui.components.Button
import com.aira.agent.ui.components.ButtonVariant
import com.aira.agent.ui.components.Icon
import com.aira.agent.ui.components.IconButton
import com.aira.agent.ui.components.IconButtonSize
import com.aira.agent.ui.components.Markdown
import com.aira.agent.ui.components.ScreenScaffold
import com.aira.agent.ui.components.StatusBanner
import com.aira.agent.ui.components.Text
import com.aira.agent.ui.theme.ArrowClockwise
import com.aira.agent.ui.theme.CaretLeft
import com.aira.agent.ui.theme.CaretRight
import com.aira.agent.ui.theme.Globe
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.LockSimple
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.X

// In-app browser: 52dp synced top chrome over a 2dp Electric Blue progress
// bar, a theme-aware native empty-state card when no page is open (the
// WebView is never mounted for a null page, so Midnight never flashes
// white), a 56dp StatusBanner for page errors, and a 28dp-radius bottom
// inspector tray (Page / Extracted / Links) with 52dp outline link rows and a
// Save to Memory action that stamps the source page URL.
//
// The WebView instance is retained in WebViewHolder and detached from its
// previous parent on re-attach, so it survives tab switches and `open`
// commands from Command never fail.
//
// Ticket 41/NG-5: the address pill forwards raw text to onOpenUrl
// (AgentViewModel.openWeb), which routes through the shared allowlist
// (routeBrowserUrl/isAllowedWebUrl) and refuses non-web addresses with an
// explanation — the Browser tab holds no second policy.
@Composable
fun BrowserScreen(
    state: AgentUiState,
    onOpenUrl: (String) -> Unit,
    onAttachBridge: (WebBridge) -> Unit,
    onBack: () -> Unit = {},
    onSaveMemory: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val page = state.webPage
    var url by rememberSaveable(page?.url) { mutableStateOf(page?.url ?: "") }
    var panel by rememberSaveable { mutableStateOf("Page") }
    val progress by WebViewHolder.progress.collectAsStateWithLifecycle()
    val canGoBack by WebViewHolder.canGoBack.collectAsStateWithLifecycle()
    val canGoForward by WebViewHolder.canGoForward.collectAsStateWithLifecycle()
    val chrome = browserChromeState(page, url, progress, state.webLoading, canGoBack, canGoForward)
    // Recently visited pages for the empty state, newest first.
    val recentUrls =
        remember(state.interactions) {
            state.interactions.mapNotNull { it.webPage?.url?.takeIf { u -> u.isNotBlank() } }
                .distinct().take(5)
        }

    ScreenScaffold(modifier) {
        Column(Modifier.fillMaxSize()) {
            // 56dp dark top chrome with 48dp hit targets and a 40dp single-line URL pill.
            // statusBarsPadding keeps the bar pinned below the status icons so
            // the white WebView never bleeds behind them (a no-op when the
            // scaffold already consumed the inset).
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.colors.background)
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack, contentDescription = "Close Browser", size = IconButtonSize.Small) {
                    Icon(Icons.X, contentDescription = null, modifier = Modifier.size(20.dp), tint = Theme.colors.onBackground)
                }
                IconButton(onClick = { WebViewHolder.goBack() }, contentDescription = "Back", enabled = chrome.canGoBack, size = IconButtonSize.Small) {
                    Icon(Icons.CaretLeft, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { WebViewHolder.goForward() }, contentDescription = "Forward", enabled = chrome.canGoForward, size = IconButtonSize.Small) {
                    Icon(Icons.CaretRight, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                // 40dp URL pill: lock for an open https page, globe otherwise.
                // Text syncs to the open page; typing + Go opens a new page.
                Row(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Theme.colors.surfaceVariant)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (chrome.showSecureIcon) Icons.LockSimple else Icons.Globe,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Theme.colors.onBackgroundMuted,
                    )
                    BasicTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        textStyle = Theme.typography.label.copy(color = Theme.colors.onSurfaceVariant),
                        cursorBrush = SolidColor(Theme.colors.primary),
                        singleLine = true,
                        maxLines = 1,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onOpenUrl(url) }),
                        decorationBox = { field ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                if (url.isEmpty()) {
                                    Text(
                                        "Paste a link, then Go",
                                        style = Theme.typography.label,
                                        color = Theme.colors.onBackgroundMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                field()
                            }
                        },
                    )
                }
                IconButton(onClick = { WebViewHolder.reload() }, contentDescription = "Reload", enabled = chrome.canReload, size = IconButtonSize.Small) {
                    Icon(Icons.ArrowClockwise, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }

            // 2dp Electric Blue progress bar, solid primary — no alpha.
            if (chrome.showProgress) {
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    Box(
                        Modifier.fillMaxWidth(chrome.progressFraction)
                            .height(2.dp)
                            .background(Theme.colors.primary),
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

            if (chrome.showEmptyState) {
                // Dark AIRA Browser empty state: the WebView stays unmounted
                // while no page is open, so Midnight never flashes white.
                // Shows recently visited pages plus the available page Tools.
                Box(
                    Modifier.weight(1f).fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        Box(
                            Modifier.size(56.dp)
                                .clip(Theme.shapes.fullyRounded)
                                .background(Theme.colors.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Globe,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = Theme.colors.primary,
                            )
                        }
                        Text(
                            "AIRA Browser",
                            style = Theme.typography.title.copy(textAlign = TextAlign.Center),
                            color = Theme.colors.onBackground,
                        )
                        Text(
                            "Ask the Agent to open a link, or paste one above.",
                            style = Theme.typography.body.copy(textAlign = TextAlign.Center),
                            color = Theme.colors.onBackgroundMuted,
                        )
                        if (recentUrls.isNotEmpty()) {
                            Text(
                                "Recently visited",
                                style = Theme.typography.caption.copy(textAlign = TextAlign.Center),
                                color = Theme.colors.onBackgroundMuted,
                            )
                            recentUrls.forEach { visited ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, Theme.colors.border, RoundedCornerShape(12.dp))
                                        .clickable { onOpenUrl(visited) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Globe,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = Theme.colors.onBackgroundMuted,
                                    )
                                    Text(
                                        visited,
                                        style = Theme.typography.label,
                                        color = Theme.colors.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        Text(
                            "Available Tools",
                            style = Theme.typography.caption.copy(textAlign = TextAlign.Center),
                            color = Theme.colors.onBackgroundMuted,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf("get_page_text", "extract", "click").forEach { tool ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .border(1.dp, Theme.colors.border, RoundedCornerShape(50))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        toolLabel(tool),
                                        style = Theme.typography.caption,
                                        color = Theme.colors.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val pageBackground = Theme.colors.background.toArgb()
                AndroidView(
                    factory = { context ->
                        val wv = WebViewHolder.obtain(context)
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        WebViewHolder.applyBackground(pageBackground)
                        onAttachBridge(WebViewHolder.bridge(wv))
                        wv
                    },
                    update = { WebViewHolder.applyBackground(pageBackground) },
                    modifier = Modifier.fillMaxWidth()
                        .weight(if (panel == "Page") 1f else 0.55f),
                )
            }

            // Bottom inspector tray with a large 28dp top radius and a
            // centered 32dp x 4dp drag-handle pill.
            Column(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Theme.colors.panel),
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(width = 32.dp, height = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Theme.colors.onBackground.copy(alpha = 0.16f)),
                    )
                }
                Box(
                    Modifier.fillMaxWidth()
                        .height(0.5.dp)
                        .background(Theme.colors.edge),
                )
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Theme.colors.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        "Page" to "Page",
                        "Extracted" to "Extracted",
                        "Links" to "Links (${page?.links?.size ?: 0})",
                    ).forEach { (key, label) ->
                        val selected = panel == key
                        Box(
                            Modifier.weight(1f)
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Theme.colors.surface else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable(
                                    onClick = { panel = key },
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = androidx.compose.foundation.LocalIndication.current,
                                )
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = Theme.typography.label,
                                color = if (selected) Theme.colors.onSurface else Theme.colors.onBackgroundMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
                        if (page == null) {
                            Text(
                                "Nothing opened yet — open a page to inspect it.",
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
                                onClick = { onSaveMemory(browserSaveNote(page)) },
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
                                        val interaction = remember { MutableInteractionSource() }
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .heightIn(min = 52.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .border(
                                                    1.dp,
                                                    Theme.colors.border,
                                                    RoundedCornerShape(12.dp),
                                                )
                                                .clickable(
                                                    interactionSource = interaction,
                                                    indication = androidx.compose.foundation.LocalIndication.current,
                                                    onClick = { onOpenUrl(link) },
                                                )
                                                .padding(
                                                    horizontal = Space * 3,
                                                    vertical = Space * 2,
                                                ),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Space * 2),
                                        ) {
                                            Text(
                                                link,
                                                maxLines = 1,
                                                color = Theme.colors.onBackground,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Icon(
                                                Icons.CaretRight,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = Theme.colors.onBackgroundMuted,
                                            )
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
