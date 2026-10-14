package com.uong.phormi

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Shared response/command validation for the tunnel and direct providers. */
internal object PhormiAiProtocol {
    private val actions = setOf("navigate", "click", "type", "press_enter", "scroll", "back", "done", "stuck", "wait")

    fun action(raw: String): JSONObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("AI returned text instead of a browser action. Try the command again.")
        val action = runCatching { JSONObject(raw.substring(start, end + 1)) }
            .getOrElse { throw IOException("AI returned an incomplete browser action. Try a shorter instruction.") }
        val name = action.optString("action").lowercase(java.util.Locale.ROOT)
        if (name !in actions || action.optInt("tabId", -1) <= 0) throw IOException("AI returned an unsupported action or invalid tab ID.")
        if (name == "navigate" && !httpUrl(action.optString("url"))) throw IOException("AI navigation requires an HTTP or HTTPS address.")
        if (name in setOf("click", "type") && action.optString("elementId").isBlank()) {
            throw IOException("AI did not identify a specific page control. No action was taken.")
        }
        return action.put("action", name)
    }

    fun httpUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme?.lowercase(java.util.Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun text(raw: String): String? {
        val root = runCatching { JSONObject(raw) }.getOrElse { throw IOException("The AI service returned invalid JSON.") }
        fun parts(array: JSONArray?): String = buildString {
            if (array != null) for (i in 0 until array.length()) {
                val part = array.optJSONObject(i) ?: continue
                if (part.optBoolean("thought")) continue
                val text = part.opt("text")
                if (text is String) append(text)
            }
        }
        val value: Any? = when {
            root.has("answer") -> root.opt("answer")
            root.has("choices") -> {
                val choice = root.optJSONArray("choices")?.optJSONObject(0)
                if (choice?.optString("finish_reason") == "length") throw IOException("The AI response was cut off by the provider's output limit. Try a shorter request or a model with a larger output allowance.")
                choice?.optJSONObject("message")?.opt("content") ?: choice?.opt("text")
            }
            root.has("candidates") -> {
                val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                if (candidate?.optString("finishReason") == "MAX_TOKENS") throw IOException("The AI response reached its output limit. Try a shorter request.")
                parts(candidate?.optJSONObject("content")?.optJSONArray("parts"))
            }
            root.has("content") -> parts(root.optJSONArray("content"))
            root.has("output_text") -> root.opt("output_text")
            root.has("output") -> buildString {
                val output = root.optJSONArray("output")
                if (output != null) for (i in 0 until output.length()) append(parts(output.optJSONObject(i)?.optJSONArray("content")))
            }
            else -> null
        }
        return when (value) {
            is String -> value.trim().takeIf { it.isNotEmpty() && it != "null" }
            is JSONArray -> parts(value).trim().takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    fun httpError(code: Int): IOException = IOException(when (code) {
        401, 403 -> "AI authorization was rejected (HTTP $code). Reconnect the tunnel or check the saved provider connection."
        404 -> "AI endpoint or model was not found (HTTP 404). Check the endpoint and exact model ID."
        413 -> "The AI service rejected the request size (HTTP 413). Select less text or shorten the instruction."
        429 -> "The AI service is rate-limited or out of quota (HTTP 429). Try again later or check the provider's allowance."
        in 500..599 -> "The AI service is temporarily unavailable (HTTP $code). Try again later."
        else -> "The AI request failed (HTTP $code). Check the saved connection settings."
    })
}
