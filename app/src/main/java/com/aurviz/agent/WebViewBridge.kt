package com.aurviz.agent

import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Session 2 thin wiring: a real WebView behind the WebBridge plug.
// load() blocks the CALLER (always a background thread); the WebView
// itself is driven on the UI thread. Parsing stays in Web.kt (tested).

class WebViewBridge(private val webView: WebView) : WebBridge {

    @Volatile private var lastUrl: String = ""

    override fun currentUrl(): String = lastUrl

    override fun load(url: String): String {
        lastUrl = url
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var html: String? = null
        var failed = false
        webView.post {
            webView.webViewClient =
                object : android.webkit.WebViewClient() {
                    // Ticket #107 (T2): scheme enforcement on every redirect
                    // and sub-navigation — anything outside http(s) is
                    // blocked via the shared shouldBlockWebNavigation gate.
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): Boolean = shouldBlockWebNavigation(request.url?.toString())

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
                        shouldBlockWebNavigation(url)

                    override fun onPageFinished(view: WebView, u: String) {
                        if (!finished.compareAndSet(false, true)) return
                        view.evaluateJavascript(buildOuterHtmlJs()) { value ->
                            html = unquoteJs(value)
                            done.countDown()
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (!request.isForMainFrame) return
                        if (!finished.compareAndSet(false, true)) return
                        failed = true
                        done.countDown()
                    }

                    @Suppress("DEPRECATION")
                    override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                        if (!finished.compareAndSet(false, true)) return
                        failed = true
                        done.countDown()
                    }
                }
            webView.loadUrl(url)
        }
        if (!done.await(25, TimeUnit.SECONDS)) throw WebException(WebFailure(url, "timeout"))
        if (failed) throw WebException(WebFailure(url, "load-failed"))
        return html ?: throw WebException(WebFailure(url, "load-failed"))
    }

    override fun pageText(): String =
        evalJs(buildPageTextJs(), "text")
            ?: throw WebException(WebFailure(lastUrl, "load-failed"))

    override fun click(selector: String): String {
        val status = evalJs(buildClickJs(selector), "click")
        if (status == "no-match") throw WebException(WebFailure(lastUrl, "no-match: $selector"))
        return evalJs(buildOuterHtmlJs(), "click")
            ?: throw WebException(WebFailure(lastUrl, "load-failed"))
    }

    override fun scrollBy(px: Int): String {
        evalJs(buildScrollJs(px), "scroll")
        return evalJs(buildOuterHtmlJs(), "scroll")
            ?: throw WebException(WebFailure(lastUrl, "load-failed"))
    }

    // One evaluateJavascript round-trip off the caller thread. The latch
    // timeout is the "never hangs" guarantee: a silent page still resolves
    // to a "timeout" WebException the loop can recover from.
    private fun evalJs(script: String, op: String): String? {
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var out: String? = null
        var ok = false
        webView.post {
            try {
                webView.evaluateJavascript(script) { value ->
                    if (!finished.compareAndSet(false, true)) return@evaluateJavascript
                    out = unquoteJs(value)
                    ok = true
                    done.countDown()
                }
            } catch (_: Exception) {
                if (finished.compareAndSet(false, true)) done.countDown()
            }
        }
        if (!done.await(25, TimeUnit.SECONDS)) throw WebException(WebFailure(lastUrl, "timeout"))
        if (!ok) throw WebException(WebFailure(lastUrl, "load-failed"))
        return out
    }

    // evaluateJavascript answers with a JSON-quoted string ("a\nb").
    private fun unquoteJs(value: String?): String? {
        if (value == null || value == "null") return null
        return try {
            org.json.JSONTokener(value).nextValue() as? String
        } catch (_: Exception) {
            null
        }
    }
}
