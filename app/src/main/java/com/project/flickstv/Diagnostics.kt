package com.project.flickstv

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject

private const val KEEP = 10

/** Debug-only: logs every key, page report and console message under [TAG], and draws the overlay. */
class Diagnostics(context: Context, root: FrameLayout) {
    private val androidKeys = ArrayDeque<String>()
    private val pageEvents = ArrayDeque<String>()
    private val statuses = linkedMapOf<String, String>()

    private val overlay = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 10f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xB0000000.toInt())
        setPadding(12, 8, 12, 8)
        isFocusable = false
        isClickable = false
    }

    init {
        root.addView(overlay, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    fun logEnvironment(webView: WebView) {
        val pm = webView.context.packageManager
        val tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val touch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        Log.i(TAG, "DEVICE ${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT} leanback=$tv touchscreen=$touch")

        val pkg = if (Build.VERSION.SDK_INT >= 26) {
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
        } else {
            "n/a below API 26"
        }
        val ua = webView.settings.userAgentString
        val chromium = Regex("Chrome/([\\d.]+)").find(ua)?.groupValues?.get(1)
        Log.i(TAG, "WEBVIEW package=$pkg chromium=$chromium")
        Log.i(TAG, "WEBVIEW ua=$ua")
        status("device", "${Build.MODEL} api=${Build.VERSION.SDK_INT} leanback=$tv")
        status("webview", "$pkg (Chrome/$chromium)")
    }

    fun androidKey(event: KeyEvent, handled: Boolean) {
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        val line = "${KeyEvent.keyCodeToString(event.keyCode)}(${event.keyCode}) $action rep=${event.repeatCount} " +
            "scan=${event.scanCode} src=0x${Integer.toHexString(event.source)} dev=${event.deviceId} handled=$handled"
        Log.i(TAG, "KEY android $line")
        if (event.action == KeyEvent.ACTION_DOWN) push(androidKeys, line)
    }

    fun unhandledKey(event: KeyEvent) {
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        val line = "UNHANDLED ${KeyEvent.keyCodeToString(event.keyCode)} $action"
        Log.i(TAG, "KEY webview $line")
        if (event.action == KeyEvent.ACTION_DOWN) push(androidKeys, line)
    }

    fun pageEvent(report: JSONObject) {
        val line = when (val type = report.optString("t")) {
            "keydown", "keyup" -> "$type key=${report.optString("key")} code=${report.optString("code")} " +
                "keyCode=${report.optInt("keyCode")} prevented=${report.optBoolean("prevented")} " +
                "trusted=${report.optBoolean("trusted")} target=${report.optString("target")}"
            "focus" -> "focus ${report.optString("target")}"
            "player" -> "player ${report.optString("name")} (${report.optString("where")}) ${report.optString("detail")}"
            else -> "$type ${report.optString("msg")}"
        }
        Log.i(TAG, "PAGE $line")
        if (report.optString("t") != "keyup") push(pageEvents, line)
    }

    fun console(message: ConsoleMessage) {
        val line = "CONSOLE ${message.messageLevel()} ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
        when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, line)
            ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }
    }

    fun status(key: String, value: String) {
        statuses[key] = value
        Log.i(TAG, "STATUS $key: $value")
        render()
    }

    private fun push(list: ArrayDeque<String>, line: String) {
        list.addLast(line)
        while (list.size > KEEP) list.removeFirst()
        render()
    }

    private fun render() {
        overlay.text = buildString {
            statuses.forEach { (k, v) -> append(k).append(": ").append(v.take(110)).append('\n') }
            append("-- android keys --\n")
            androidKeys.forEach { append(it).append('\n') }
            append("-- page --\n")
            pageEvents.forEach { append(it.take(140)).append('\n') }
        }.trimEnd()
    }
}
