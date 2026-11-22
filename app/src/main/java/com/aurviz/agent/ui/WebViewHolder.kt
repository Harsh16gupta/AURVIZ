package com.aurviz.agent.ui

import android.content.Context
import android.webkit.WebView
import androidx.annotation.MainThread
import com.aurviz.agent.WebBridge
import com.aurviz.agent.WebViewBridge

// Holds the single real WebView behind BrowserScreen (used by WebViewBridge).
// Retained across NavHost tab switches: the AndroidView factory detaches it
// from its previous parent instead of creating a new one, so `open`
// commands from Command never fail for lack of a page host.
object WebViewHolder {
    private var webView: WebView? = null
    private var bridge: WebBridge? = null

    @MainThread
    fun obtain(context: Context): WebView {
        webView?.let { return it }
        val created = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
        }
        webView = created
        return created
    }

    @MainThread
    fun bridge(webView: WebView): WebBridge {
        val current = bridge
        if (current is WebViewBridge) return current
        return WebViewBridge(webView).also { bridge = it }
    }
}
