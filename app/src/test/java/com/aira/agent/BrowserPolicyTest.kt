package com.aira.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 41/NG-5: Browser tab routing reuses the Tool-layer allowlist
// (isAllowedWebUrl). The Browser tab is the second caller, not a second
// policy — verdicts here must match the Tool layer, and refusals explain.
class BrowserPolicyTest {

    @Test
    fun webAddressesLoadUnchanged() {
        for (url in listOf(
            "https://example.com",
            "http://example.com/path?q=1",
            "https://example.com:8080/a?b=c#frag",
            "HTTPS://example.com",
            "  https://example.com  ",
        )) {
            val route = routeBrowserUrl(url)
            assertTrue("$url must load, got $route", route is BrowserRoute.Load)
            route as BrowserRoute.Load
            assertEquals(url.trim(), route.url)
            assertTrue("$url must pass the shared allowlist", isAllowedWebUrl(route.url))
        }
    }

    @Test
    fun nonWebAddressesRefuseWithExplanation() {
        for (bad in listOf(
            "file:///etc/passwd",
            "FILE:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hi",
            "ftp://example.com/x",
            "about:blank",
            "example.com",
            "example.com/path",
            "",
            "   ",
        )) {
            val route = routeBrowserUrl(bad)
            assertTrue("$bad must refuse, got $route", route is BrowserRoute.Refuse)
            route as BrowserRoute.Refuse
            assertTrue(
                "refusal must explain http-only: ${route.reason}",
                route.reason.contains("http", ignoreCase = true),
            )
            assertTrue("$bad must fail the shared allowlist", !isAllowedWebUrl(bad.trim()))
        }
    }

    @Test
    fun bareHostIsNotAutoUpgraded() {
        // openWeb used to prepend https://, bypassing the allowlist for bare
        // hosts. The Browser tab must refuse them like the Tool layer does.
        val route = routeBrowserUrl("example.com")
        assertTrue(route is BrowserRoute.Refuse)
    }

    // --- Ticket #107 (T2, fix 5): WebView redirect/sub-navigation gate. ---
    // shouldBlockWebNavigation is the pure JVM-testable verdict; the
    // WebViewClient overrides in WebViewBridge.kt pin the wiring.

    @Test
    fun shouldBlockWebNavigationAllowsHttpAndBlocksOthers() {
        assertFalse(shouldBlockWebNavigation("https://example.com"))
        assertFalse(shouldBlockWebNavigation("http://example.com/path?q=1"))
        assertFalse(shouldBlockWebNavigation("HTTPS://example.com"))
        assertFalse(shouldBlockWebNavigation("  https://example.com  "))
        for (bad in listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hi",
            "ftp://example.com/x",
            "about:blank",
            "intent://example.com/#Intent;scheme=https;end",
            "example.com",
            "",
            "   ",
        )) {
            assertTrue("$bad must block, got allow", shouldBlockWebNavigation(bad))
        }
        assertTrue("null must block", shouldBlockWebNavigation(null))
    }

    @Test
    fun webNavigationGateMatchesSharedAllowlist() {
        val loads = listOf("https://example.com", "http://example.com/x")
        val refuses = listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hi",
            "ftp://example.com/x",
            "example.com",
        )
        for (url in loads) {
            assertTrue(isAllowedWebUrl(url))
            assertFalse(shouldBlockWebNavigation(url))
        }
        for (url in refuses) {
            assertTrue(!isAllowedWebUrl(url))
            assertTrue(shouldBlockWebNavigation(url))
        }
    }

    @Test
    fun webViewClientOverridesEnforceSchemeGate() {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) break
            dir = dir.parentFile ?: throw AssertionError("Could not locate app module dir from user.dir")
        }
        val bridge = File(dir, "src/main/java/com/aira/agent/WebViewBridge.kt").readText()
        assertTrue(bridge.contains("shouldOverrideUrlLoading"))
        assertTrue(bridge.contains("shouldBlockWebNavigation"))
        assertTrue(bridge.contains("android.webkit.WebResourceRequest"))
    }

    @Test
    fun routingMatchesToolLayerVerdicts() {
        val loads = listOf("https://example.com", "http://example.com/x")
        val refuses = listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hi",
            "ftp://example.com/x",
            "example.com",
        )
        for (url in loads) {
            assertTrue(isAllowedWebUrl(url))
            assertTrue(routeBrowserUrl(url) is BrowserRoute.Load)
        }
        for (url in refuses) {
            assertTrue(!isAllowedWebUrl(url))
            val route = routeBrowserUrl(url)
            assertTrue(route is BrowserRoute.Refuse)
            try {
                openPage(FakeWebBridge(mapOf(url to "<html></html>")), url)
                assertTrue("openPage($url) must throw", false)
            } catch (e: WebException) {
                assertEquals("blocked", e.failure.reason)
            }
        }
    }
}
