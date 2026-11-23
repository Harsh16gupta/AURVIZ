package com.aurviz.agent.ui

import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.MainThread
import com.aurviz.agent.WebBridge
import com.aurviz.agent.WebViewBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Holds the single real WebView behind BrowserScreen (used by WebViewBridge).
// Retained across NavHost tab switches: the AndroidView factory detaches it
// from its previous parent instead of creating a new one, so `open`
// commands from Command never fail for lack of a page host.
object WebViewHolder {
    private var webView: WebView? = null
    private var bridge: WebBridge? = null

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    @MainThread
    @Suppress("DEPRECATION")
    fun obtain(context: Context): WebView {
        webView?.let { return it }
        // Ticket 41/NG-5 page-host posture: JavaScript stays enabled for the
        // existing text-extraction-only usage (buildExtractJs/buildClickJs and
        // the outer-HTML read in WebViewBridge — no native bridge is added),
        // while file and content access stay off so page content can never
        // reach local files. Mixed content is pinned to NEVER_ALLOW.
        val created = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Progress + navigation state only — WebViewBridge's WebViewClient
            // is untouched.
            webChromeClient =
                object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        _progress.value = newProgress
                        _canGoBack.value = view.canGoBack()
                        _canGoForward.value = view.canGoForward()
                        super.onProgressChanged(view, newProgress)
                    }
                }
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

    @MainThread
    fun goBack() {
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    @MainThread
    fun goForward() {
        webView?.takeIf { it.canGoForward() }?.goForward()
    }

    @MainThread
    fun reload() {
        webView?.reload()
    }
}
