package com.aira.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Issue #88: Browser chrome sync + empty-state gating + Memory save copy.
class BrowserChromeUiTest {

    private fun page(url: String = "https://example.com") =
        WebPage(url = url, title = "Example", text = "Hello world", links = listOf("https://example.com/a"))

    @Test
    fun `null page shows empty state and globe pill`() {
        val chrome = browserChromeState(null, "", 0, false, false, false)
        assertTrue(chrome.showEmptyState)
        assertFalse(chrome.showSecureIcon)
        assertFalse(chrome.canReload)
    }

    @Test
    fun `https page shows lock icon and synced pill url`() {
        val chrome = browserChromeState(page("https://example.com/x"), "stale draft", 0, false, true, false)
        assertFalse(chrome.showEmptyState)
        assertTrue(chrome.showSecureIcon)
        assertEquals("https://example.com/x", chrome.pillText)
        assertTrue(chrome.canGoBack)
        assertFalse(chrome.canGoForward)
        assertTrue(chrome.canReload)
    }

    @Test
    fun `http page shows globe icon`() {
        val chrome = browserChromeState(page("http://example.com"), "", 0, false, false, false)
        assertFalse(chrome.showSecureIcon)
    }

    @Test
    fun `progress bar shows while loading and tracks fraction`() {
        val loading = browserChromeState(page(), "", 0, true, false, false)
        assertTrue(loading.showProgress)
        val mid = browserChromeState(page(), "", 42, false, false, false)
        assertTrue(mid.showProgress)
        assertEquals(0.42f, mid.progressFraction, 0.001f)
        val idle = browserChromeState(page(), "", 100, false, false, false)
        assertFalse(idle.showProgress)
    }

    @Test
    fun `save note stamps source page url`() {
        val note = browserSaveNote(page("https://example.com/x"))
        assertTrue(note.contains("https://example.com/x"))
        assertTrue(note.contains("Example"))
    }

    @Test
    fun `secure check is case-insensitive https only`() {
        assertTrue(isSecureBrowserUrl("HTTPS://example.com"))
        assertFalse(isSecureBrowserUrl("http://example.com"))
        assertFalse(isSecureBrowserUrl(""))
    }
}
