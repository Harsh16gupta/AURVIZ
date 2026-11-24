package com.aurviz.agent

import com.aurviz.agent.WebPage

// Pure seam for BrowserScreen chrome (issue #88): keeps URL-pill sync,
// empty-state gating, and Memory save copy testable without Compose.
//
// Glossary: Memory only. No raw tool IDs, no banned words.
data class BrowserChromeState(
    val showEmptyState: Boolean,
    val pillText: String,
    val showSecureIcon: Boolean,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val canReload: Boolean,
    val showProgress: Boolean,
    val progressFraction: Float,
)

// https pages show the lock icon; anything else shows the globe.
fun isSecureBrowserUrl(url: String): Boolean =
    url.trim().lowercase().startsWith("https://")

// Syncs the 52dp top chrome to state: the pill mirrors the open page URL
// and falls back to the typed draft when no page is open.
fun browserChromeState(
    webPage: WebPage?,
    draftInput: String,
    progress: Int,
    webLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
): BrowserChromeState {
    val pillText = webPage?.url ?: draftInput
    return BrowserChromeState(
        showEmptyState = webPage == null,
        pillText = pillText,
        showSecureIcon = webPage != null && isSecureBrowserUrl(webPage.url),
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        canReload = webPage != null,
        showProgress = progress in 1..99 || webLoading,
        progressFraction = if (progress in 1..99) progress / 100f else 1f,
    )
}

// Inspector "Save to Memory" note: always stamps the source page URL so the
// kept note traces back to where it was found (mirrors pageSource tagging).
fun browserSaveNote(page: WebPage): String {
    val title = page.title.ifBlank { page.url }
    return "Web note ($title) [${page.url}]: ${page.text.take(220)}"
}
