package com.uong.phormi

import android.webkit.WebView
import org.json.JSONObject

/** Browser-side WebGPU capability check. This never claims that a model runtime is installed. */
object PhormiWebGpuDiagnostics {
    private val SCRIPT = """
        (() => {
          const gpu = navigator && navigator.gpu;
          const info = {
            secureContext: !!window.isSecureContext,
            navigatorGpu: !!gpu,
            adapterRequestPossible: !!(gpu && gpu.requestAdapter),
            userAgent: navigator.userAgent || ''
          };
          return JSON.stringify(info);
        })()
    """.trimIndent()

    fun run(webView: WebView, callback: (JSONObject) -> Unit) {
        webView.evaluateJavascript(SCRIPT) { raw ->
            val text = raw?.let { decodeJavascriptString(it) }.orEmpty()
            val result = runCatching { JSONObject(text) }
                .getOrElse { JSONObject().put("ok", false).put("error", "invalid_diagnostics_result") }
                .put("ok", text.isNotBlank())
            callback(result)
        }
    }

    private fun decodeJavascriptString(raw: String): String {
        if (raw == "null" || raw.isBlank()) return ""
        return try {
            org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
        } catch (_: Exception) {
            raw.trim('"')
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
        }
    }
}
