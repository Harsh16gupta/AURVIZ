package com.aurviz.agent

import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Session 2 thin wiring: a real WebView behind the WebBridge plug.
// load() blocks the CALLER (always a background thread); the WebView
// itself is driven on the UI thread. Parsing stays in Web.kt (tested).

class WebViewBridge(private val webView: WebView) : WebBridge {

    override fun load(url: String): String {
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        var html: String? = null
        var failed = false
        webView.post {
            webView.webViewClient =
                object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        if (!finished.compareAndSet(false, true)) return
                        view.evaluateJavascript("(function(){return document.documentElement.outerHTML})()") { value ->
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
