package com.project.flickstv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONException
import org.json.JSONObject

const val TAG = "FlicksTV"
private const val DEFAULT_URL = "https://flicks.flamapp.live/vuvvqo"
private const val BACK_VERDICT_TIMEOUT_MS = 1000L

// Our convention, not a platform mapping: WebView 143 returns false from
// dispatchKeyEvent(KEYCODE_BACK) and never delivers it to the page, so the page
// gets this untrusted KeyboardEvent instead.
private const val SYNTHETIC_BACK_KEY = "BrowserBack"
private const val SYNTHETIC_BACK_KEYCODE = 166

/**
 * Full-screen WebView for one Flick. Launch with `--es url <url>` to load another one.
 *
 * Back goes to the page first: as a real key event if WebView accepts it, otherwise as a
 * synthetic [SYNTHETIC_BACK_KEY] keydown/keyup. The first of these decides:
 *  - page called preventDefault on the Back keydown -> STAY
 *  - page keydown reported without it, or WebView reports the key unhandled -> EXIT
 *  - no page report within [BACK_VERDICT_TIMEOUT_MS] -> EXIT
 * Every decision is logged as "BACK decision: ..." under [TAG].
 */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var diag: Diagnostics? = null
    private val main = Handler(Looper.getMainLooper())
    // probe.js reports what the page receives; nav.js gives the skin controls D-pad focus.
    private val pageJs by lazy {
        listOf("probe.js", "nav.js").joinToString("\n") { name -> assets.open(name).bufferedReader().use { it.readText() } }
    }

    private var backPending = false
    private val backTimeout = Runnable {
        decideBack(exit = true, "page reported no keydown within ${BACK_VERDICT_TIMEOUT_MS}ms")
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = Client()
            webChromeClient = Chrome()
            addJavascriptInterface(PageBridge(), "FlicksProbe")
        }

        val root = FrameLayout(this)
        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (BuildConfig.DEBUG) diag = Diagnostics(this, root).also { it.logEnvironment(webView) }
        setContentView(root)
        webView.requestFocus()

        val url = intent.getStringExtra("url") ?: DEFAULT_URL
        Log.i(TAG, "LOAD $url")
        webView.loadUrl(url)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            val first = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
            if (first) {
                backPending = true
                main.postDelayed(backTimeout, BACK_VERDICT_TIMEOUT_MS)
            }
            val handled = webView.dispatchKeyEvent(event)
            diag?.androidKey(event, handled)
            if (!handled) dispatchSyntheticBack(event)
            if (first) {
                val via = if (handled) "native key event" else "synthetic $SYNTHETIC_BACK_KEY (WebView declined the key)"
                Log.i(TAG, "BACK forwarded to page via $via, awaiting verdict")
            }
            return true
        }
        val handled = super.dispatchKeyEvent(event)
        diag?.androidKey(event, handled)
        return handled
    }

    private fun dispatchSyntheticBack(event: KeyEvent) {
        val type = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
        val js = "(function(){var t=document.activeElement||document.body||document.documentElement;" +
            "var e=new KeyboardEvent('$type',{key:'$SYNTHETIC_BACK_KEY',keyCode:$SYNTHETIC_BACK_KEYCODE," +
            "which:$SYNTHETIC_BACK_KEYCODE,repeat:${event.repeatCount > 0},bubbles:true,cancelable:true,composed:true});" +
            "return t.dispatchEvent(e)?'not-prevented':'prevented';})()"
        val first = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        webView.evaluateJavascript(js) { result ->
            if (!first) return@evaluateJavascript
            when (result) {
                "\"prevented\"" -> decideBack(exit = false, "page called preventDefault on synthetic $SYNTHETIC_BACK_KEY")
                "\"not-prevented\"" -> decideBack(exit = true, "page did not preventDefault synthetic $SYNTHETIC_BACK_KEY")
                else -> decideBack(exit = true, "synthetic $SYNTHETIC_BACK_KEY dispatch failed, result=$result")
            }
        }
    }

    private fun decideBack(exit: Boolean, reason: String) {
        if (!backPending) return
        backPending = false
        main.removeCallbacks(backTimeout)
        val verdict = if (exit) "EXIT" else "STAY"
        Log.i(TAG, "BACK decision: $verdict - $reason")
        diag?.status("back", "$verdict - $reason")
        if (exit) finish()
    }

    private fun onPageReport(json: String) {
        val report = try {
            JSONObject(json)
        } catch (e: JSONException) {
            Log.w(TAG, "PAGE unparseable probe report: $json")
            return
        }
        diag?.pageEvent(report)
        if (backPending && report.optString("t") == "keydown") {
            val seen = "key=${report.optString("key")} keyCode=${report.optInt("keyCode")} trusted=${report.optBoolean("trusted")}"
            if (report.optBoolean("prevented")) {
                decideBack(exit = false, "page called preventDefault on keydown $seen")
            } else {
                decideBack(exit = true, "page received keydown $seen but did not preventDefault")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    private inner class PageBridge {
        @JavascriptInterface
        fun post(json: String) {
            main.post { onPageReport(json) }
        }
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            diag?.status("load", "started $url")
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            view.evaluateJavascript(pageJs, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(pageJs, null)
            diag?.status("load", "finished $url")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            val where = if (request.isForMainFrame) "MAIN FRAME" else "subresource"
            Log.e(TAG, "LOAD ERROR ($where) ${error.errorCode} ${error.description} ${request.url}")
            if (request.isForMainFrame) diag?.status("load", "ERROR ${error.errorCode} ${error.description}")
        }

        @Deprecated("Only called below API 23")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
            Log.e(TAG, "LOAD ERROR $errorCode $description $failingUrl")
            diag?.status("load", "ERROR $errorCode $description")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            val where = if (request.isForMainFrame) "MAIN FRAME" else "subresource"
            Log.e(TAG, "LOAD HTTP ${response.statusCode} ($where) ${request.url}")
            if (request.isForMainFrame) diag?.status("load", "HTTP ${response.statusCode}")
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            Log.e(TAG, "LOAD SSL ERROR ${error.primaryError} ${error.url} - cancelled")
            handler.cancel()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e(TAG, "RENDERER GONE didCrash=${detail.didCrash()} - recreating activity")
            recreate()
            return true
        }

        override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) {
            diag?.unhandledKey(event)
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    decideBack(exit = true, "WebView reported Back unhandled by the page")
                }
                return
            }
            super.onUnhandledKeyEvent(view, event)
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val d = diag ?: return false
            d.console(message)
            return true
        }
    }
}
