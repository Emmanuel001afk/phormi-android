package com.uong.phormi

import java.net.URI
import java.util.Locale

/** Local guardrails around model-proposed browser actions. A model's JSON is never trusted as authority. */
object PhormiBrowserAiSafety {
    fun allowedUrl(value: String): Boolean = runCatching {
        val url = URI(value.trim())
        url.scheme?.lowercase(Locale.US) in setOf("https", "http") &&
            !url.host.isNullOrBlank() && url.rawUserInfo == null
    }.getOrDefault(false)

    fun requiresConfirmation(action: String, label: String): Boolean {
        if (action != "click" && action != "press_enter") return false
        if (action == "press_enter") return true // Enter can submit a form; review the page first.
        val text = label.trim().lowercase(Locale.US)
        return listOf("send", "submit", "confirm", "buy", "purchase", "checkout", "pay", "order",
            "transfer", "delete", "remove", "publish", "post", "sign in", "log in", "authorize",
            "accept", "upload", "save changes").any { keyword ->
            Regex("(?:^|\\W)${Regex.escape(keyword)}(?:$|\\W)").containsMatchIn(text)
        }
    }

    fun safeSummary(value: String): String = value.trim().replace(Regex("\\s+"), " ").take(350)
}
