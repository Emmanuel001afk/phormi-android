package com.uong.phormi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Dynamic AI providers + fallback.
 *
 * User can save any number of providers: display name, API key, endpoint, model.
 * Built-in templates (Groq, DeepSeek, OpenAI, Gemini) are only defaults for first setup.
 * When one fails or has no key, the next saved provider is tried (fallback / assist chain).
 */
class AiController(private val context: Context) {

    companion object {
        private const val TAG = "AiController"
        private const val PREFS_NAME = "phormi_ai_prefs"
        private const val KEY_PROVIDERS = "custom_providers_json"
        private const val KEY_ACTIVE = "ai_active"
        private const val MAX_STEPS = 25
        private const val REQUEST_TIMEOUT_SECONDS = 45L
        private const val MAX_HISTORY_ENTRIES = 12

        /** Starter templates — user can still add their own names/endpoints. */
        val TEMPLATES = listOf(
            Provider("grok", "Grok", "https://api.x.ai/v1/chat/completions", "grok-4.6", ""),
            Provider("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile", ""),
            Provider("deepseek", "DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat", ""),
            Provider("openai", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4.1-mini", ""),
            Provider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-3.7-flash", "")
        )
    }

    data class ProviderConfig(val endpoint: String, val model: String)

    fun inferProviderConfig(name: String, endpoint: String = "", model: String = ""): ProviderConfig {
        val n = name.trim().lowercase()
        val inferred = when {
            n.contains("grok") || n.contains("xai") -> ProviderConfig(
                "https://api.x.ai/v1/chat/completions", ""
            )
            n.contains("gemini") || n.contains("google") -> ProviderConfig(
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", ""
            )
            n.contains("groq") -> ProviderConfig(
                "https://api.groq.com/openai/v1/chat/completions", ""
            )
            n.contains("deepseek") -> ProviderConfig(
                "https://api.deepseek.com/chat/completions", ""
            )
            n.contains("openai") || n.contains("chatgpt") || n == "gpt" -> ProviderConfig(
                "https://api.openai.com/v1/chat/completions", ""
            )
            n.contains("openrouter") -> ProviderConfig(
                "https://openrouter.ai/api/v1/chat/completions", ""
            )
            n.contains("mistral") || n.contains("le chat") -> ProviderConfig(
                "https://api.mistral.ai/v1/chat/completions", ""
            )
            n.contains("perplexity") -> ProviderConfig(
                "https://api.perplexity.ai/chat/completions", ""
            )
            n.contains("together") -> ProviderConfig(
                "https://api.together.xyz/v1/chat/completions", ""
            )
            n.contains("cerebras") -> ProviderConfig(
                "https://api.cerebras.ai/v1/chat/completions", ""
            )
            n.contains("fireworks") -> ProviderConfig(
                "https://api.fireworks.ai/inference/v1/chat/completions", ""
            )
            n.contains("huggingface") || n.contains("hugging face") -> ProviderConfig(
                "https://router.huggingface.co/v1/chat/completions", ""
            )
            n.contains("ai21") || n.contains("jamba") -> ProviderConfig(
                "https://api.ai21.com/studio/v1/chat/completions", ""
            )
            n.contains("cohere") -> ProviderConfig(
                "https://api.cohere.com/compatibility/v1/chat/completions", ""
            )
            n.contains("sambanova") || n.contains("samba nova") -> ProviderConfig(
                "https://api.sambanova.ai/v1/chat/completions", ""
            )
            n.contains("nvidia") || n.contains("nim") -> ProviderConfig(
                "https://integrate.api.nvidia.com/v1/chat/completions", ""
            )
            n.contains("qwen") || n.contains("dashscope") -> ProviderConfig(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", ""
            )
            n.isBlank() && endpoint.isBlank() -> ProviderConfig("", "")
            n.isBlank() && model.isBlank() && endpoint.isBlank() -> ProviderConfig("", "")
            else -> when {
                name.startsWith("xai-", true) -> ProviderConfig("https://api.x.ai/v1/chat/completions", "")
                name.startsWith("gsk_", true) -> ProviderConfig("https://api.groq.com/openai/v1/chat/completions", "")
                name.startsWith("pplx-", true) -> ProviderConfig("https://api.perplexity.ai/chat/completions", "")
                else -> ProviderConfig(endpoint.trim(), model.trim())
            }
        }
        return ProviderConfig(
            endpoint = endpoint.trim().ifBlank { inferred.endpoint },
            model = model.trim().ifBlank { inferred.model }
        )
    }

    suspend fun resolveProviderConfig(name: String, apiKey: String): ProviderConfig = withContext(Dispatchers.IO) {
        val inferred = inferProviderConfig(name)
        if (inferred.endpoint.isBlank()) return@withContext inferred
        val modelsUrl = when {
            inferred.endpoint.contains("/chat/completions") -> inferred.endpoint.substringBefore("/chat/completions") + "/models"
            inferred.endpoint.endsWith("/") -> inferred.endpoint + "models"
            else -> inferred.endpoint.substringBeforeLast('/') + "/models"
        }
        val request = Request.Builder().url(modelsUrl).get()
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json").build()
        val discoveredModels = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<String>()
                val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("data") ?: return@use emptyList<String>()
                buildList {
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id").orEmpty().trim()
                        val lower = id.lowercase()
                        if (id.isNotBlank() && !lower.contains("embedding") && !lower.contains("image") && !lower.contains("audio") && !lower.contains("tts") && !lower.contains("moderation")) add(id)
                    }
                }
            }
        }.getOrDefault(emptyList())

        // Some providers do not expose /models even though their chat endpoint works.
        // In that case use a known provider default and verify it with a real request.
        val model = discoveredModels.maxByOrNull { modelScore(it) }
            ?: inferProviderConfig(name).model.ifBlank {
                throw IOException("${name.trim()} has no discoverable text model. Use a supported provider name.")
            }
        val resolved = ProviderConfig(inferred.endpoint, model)
        val verifyBody = JSONObject().put("model", resolved.model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK.")))
        val verifyRequest = Request.Builder().url(resolved.endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(verifyBody.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(verifyRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val error = response.body?.string().orEmpty().replace(Regex("\\s+"), " ").take(180)
                throw IOException("API verification HTTP ${response.code}${if (error.isNotBlank()) ": $error" else ""}")
            }
        }
        resolved
    }

    private fun modelScore(id: String): Int {
        val n = id.lowercase()
        var score = 0
        val preferred = listOf("gpt-5" to 80, "gpt-4.1" to 70, "o3" to 65, "o4" to 65, "opus" to 60, "sonnet" to 58, "pro" to 55, "reason" to 45, "70b" to 35, "72b" to 35, "32b" to 25, "latest" to 20, "flash" to 18, "mini" to 10, "8b" to 2)
        preferred.forEach { (token, value) -> if (n.contains(token)) score += value }
        if (n.contains("instruct") || n.contains("chat")) score += 10
        if (n.contains("embedding") || n.contains("vision") || n.contains("image") || n.contains("audio")) score -= 1000
        return score
    }

    data class Provider(
        val id: String,
        val name: String,
        val endpoint: String,
        val model: String,
        val apiKey: String
    )

    private data class HistoryEntry(
        val stepNumber: Int,
        val provider: String,
        val actionTaken: String,
        val screenSummary: String
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<HistoryEntry>()

    fun listProviders(): List<Provider> {
        val raw = prefs.getString(KEY_PROVIDERS, null)
        if (raw.isNullOrBlank()) {
            // Migrate old single keys if present
            val migrated = TEMPLATES.map { t ->
                val key = prefs.getString("key_${t.id}", null)?.trim().orEmpty()
                t.copy(apiKey = key)
            }.filter { it.apiKey.isNotBlank() }
            if (migrated.isNotEmpty()) {
                saveProviders(migrated)
                return migrated
            }
            return emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Provider>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "AI")
                val inferred = inferProviderConfig(name, o.optString("endpoint", ""), o.optString("model", ""))
                out.add(
                    Provider(
                        id = o.optString("id", "p$i"),
                        name = name,
                        endpoint = inferred.endpoint,
                        model = inferred.model,
                        apiKey = o.optString("apiKey", "")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveProviders(list: List<Provider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("endpoint", p.endpoint)
                    .put("model", p.model)
                    .put("apiKey", p.apiKey)
            )
        }
        prefs.edit().putString(KEY_PROVIDERS, arr.toString()).commit()
    }

    fun upsertProvider(provider: Provider) {
        val list = listProviders().toMutableList()
        // Provider IDs are unique per saved key. Saving another key for the same
        // provider name therefore creates another fallback entry instead of
        // silently replacing the previous key.
        val idx = list.indexOfFirst { it.id == provider.id }
        if (idx >= 0) list[idx] = provider else list.add(provider)
        saveProviders(list.filter { it.apiKey.isNotBlank() && it.endpoint.isNotBlank() })
    }

    fun removeProvider(id: String) {
        saveProviders(listProviders().filterNot { it.id == id })
    }

    fun hasAnyKey(): Boolean = listProviders().any { it.apiKey.isNotBlank() }

    fun isActive(): Boolean = prefs.getBoolean(KEY_ACTIVE, false) && hasAnyKey()

    fun setActive(active: Boolean) { prefs.edit().putBoolean(KEY_ACTIVE, active).apply() }

    fun keyStatusSummary(): String {
        val list = listProviders()
        if (list.isEmpty()) return "No AI providers saved yet.\nAdd a name + API key below."
        return list.joinToString("\n") { "${it.name}: ready" }
    }

    suspend fun synthesizeSearchAnswer(query: String, evidence: String): String? = withContext(Dispatchers.IO) {
        val provider = listProviders().firstOrNull { it.apiKey.isNotBlank() && it.endpoint.isNotBlank() && it.model.isNotBlank() }
            ?: return@withContext null
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are the unified search assistant inside a browser. Combine the supplied search results into one coherent answer. Do not repeat the same fact. Clearly separate uncertainty. Keep the answer concise but useful."))
            .put(JSONObject().put("role", "user").put("content", "Question: $query\n\nSearch evidence:\n$evidence"))
        val body = JSONObject().put("model", provider.model).put("messages", messages)
        val request = Request.Builder()
            .url(provider.endpoint)
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val root = JSONObject(response.body?.string().orEmpty())
                root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { null }
    }

    suspend fun runTask(instruction: String, onStatus: (String) -> Unit) {
        if (!isActive()) {
            onStatus("AI is inactive. Save & Run an AI provider first.")
            return
        }
        val service = PhormiAccessibilityService.instance
        if (service == null) {
            onStatus("Enable Phormi under Settings → Accessibility, then press Run.")
            return
        }
        if (!hasAnyKey()) {
            onStatus("Save at least one AI provider (name + API key) first.")
            return
        }

        history.clear()
        var stepsTaken = 0
        var done = false

        while (!done && stepsTaken < MAX_STEPS) {
            stepsTaken++
            val screenJson = withContext(Dispatchers.Main.immediate) { service.readScreen() }
            val outcome = askAiForNextAction(instruction, screenJson) { onStatus(it) }
            if (outcome == null) {
                onStatus("All providers failed or returned empty — check keys and endpoints.")
                return
            }
            val (decision, usedProvider) = outcome
            val actionDescription = when (decision.optString("action")) {
                "tap" -> {
                    val x = decision.optInt("x", -1)
                    val y = decision.optInt("y", -1)
                    if (x >= 0 && y >= 0) {
                        withContext(Dispatchers.Main.immediate) { service.tapAt(x, y) }
                        delay(450)
                        onStatus("Step $stepsTaken ($usedProvider): tapped ($x, $y)")
                    }
                    "tapped ($x, $y)"
                }
                "type" -> {
                    val text = decision.optString("text", "")
                    val ok = withContext(Dispatchers.Main.immediate) { service.typeIntoFocusedField(text) }
                    delay(250)
                    onStatus("Step $stepsTaken ($usedProvider): typed ${if (ok) "\"$text\"" else "(field not editable)"}")
                    "typed \"$text\""
                }
                "scroll" -> {
                    val direction = decision.optString("direction", "down").lowercase()
                    val ok = withContext(Dispatchers.Main.immediate) { service.scroll(direction) }
                    delay(350)
                    onStatus("Step $stepsTaken ($usedProvider): scroll $direction${if (ok) "" else " (no scrollable view)"}")
                    "scrolled $direction"
                }
                "back" -> {
                    val ok = withContext(Dispatchers.Main.immediate) { service.goBack() }
                    delay(350)
                    onStatus("Step $stepsTaken ($usedProvider): back")
                    "back (${if (ok) "handled" else "not handled"})"
                }
                "home" -> {
                    val ok = withContext(Dispatchers.Main.immediate) { service.goHome() }
                    delay(350)
                    onStatus("Step $stepsTaken ($usedProvider): home")
                    "home (${if (ok) "handled" else "not handled"})"
                }
                "done" -> {
                    done = true
                    val summary = decision.optString("summary", "done")
                    onStatus("Finished ($usedProvider): $summary")
                    "done: $summary"
                }
                "stuck" -> {
                    done = true
                    val summary = decision.optString("summary", "stuck")
                    onStatus("Stuck ($usedProvider): $summary")
                    "stuck: $summary"
                }
                else -> {
                    done = true
                    onStatus("Step $stepsTaken ($usedProvider): no clear action, stopping.")
                    "no clear action"
                }
            }
            history.add(
                HistoryEntry(stepsTaken, usedProvider, actionDescription, summarizeScreenForHistory(screenJson))
            )
            if (history.size > MAX_HISTORY_ENTRIES) history.removeAt(0)
        }
        if (!done) onStatus("Stopped after $MAX_STEPS steps.")
    }

    private suspend fun askAiForNextAction(
        instruction: String,
        screenJson: String,
        onStatus: (String) -> Unit
    ): Pair<JSONObject, String>? {
        // Fallback chain: try each saved provider in order
        for (provider in listProviders()) {
            if (provider.apiKey.isBlank() || provider.endpoint.isBlank()) continue
            try {
                val result = callProvider(provider, instruction, screenJson)
                if (result != null) return result to provider.name
            } catch (e: Exception) {
                val message = e.message?.replace(Regex("\\s+"), " ")?.take(180).orEmpty()
                Log.w(TAG, "${provider.name} failed: $message")
                onStatus("${provider.name} failed: ${message.ifBlank { "request error" }}")
            }
        }
        return null
    }

    private fun buildHistoryText(): String {
        if (history.isEmpty()) return "(no steps yet)"
        return history.joinToString("\n") {
            "Step ${it.stepNumber} [${it.provider}] — ${it.screenSummary} → ${it.actionTaken}"
        }
    }

    private fun summarizeScreenForHistory(screenJson: String): String =
        if (screenJson.length > 200) screenJson.take(200) + "…" else screenJson

    private suspend fun callProvider(
        provider: Provider,
        instruction: String,
        screenJson: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val systemPrompt = """
            You control a phone screen one step at a time.
            Goal, step history, and visible UI elements (JSON) are provided.
            Reply with ONLY one JSON object. Supported actions:
            {"action":"tap","x":123,"y":456}
            {"action":"type","text":"..."}
            {"action":"scroll","direction":"down"}
            {"action":"back"}
            {"action":"home"}
            {"action":"done","summary":"..."}
            {"action":"stuck","summary":"..."}
            Use tap/type/scroll/back/home to operate the browser itself.
            Wait for the next screen state after each action; do not invent coordinates.
        """.trimIndent()

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    "Goal: $instruction\n\nSteps so far:\n${buildHistoryText()}\n\nScreen:\n$screenJson"
                )
            )

        val body = JSONObject()
            .put("model", provider.model)
            .put("messages", messages)

        val requestBuilder = Request.Builder()
            .url(provider.endpoint)
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .addHeader("Content-Type", "application/json")
        if (provider.endpoint.contains("generativelanguage.googleapis.com")) {
            requestBuilder.addHeader("x-goog-api-client", "Phormi/1.0")
        }
        val request = requestBuilder
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty().replace(Regex("\\s+"), " ").take(220)
                throw IOException("HTTP ${response.code}${if (errorBody.isNotBlank()) ": $errorBody" else ""}")
            }
            val responseBody = response.body?.string() ?: return@withContext null
            val root = JSONObject(responseBody)
            val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val contentValue = message?.opt("content")
            val content = when (contentValue) {
                is String -> contentValue
                is JSONArray -> buildString {
                    for (i in 0 until contentValue.length()) {
                        val part = contentValue.opt(i)
                        if (part is JSONObject) append(part.optString("text", ""))
                        else if (part is String) append(part)
                    }
                }
                else -> ""
            }.trim()
            if (content.isBlank()) {
                throw IOException("${provider.name} returned no assistant content")
            }
            val cleaned = content
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) {
                throw IOException("${provider.name} returned a response without an actionable JSON command")
            }
            runCatching { JSONObject(cleaned.substring(start, end + 1)) }
                .getOrElse { throw IOException("${provider.name} returned invalid JSON command: ${it.message ?: "parse error"}") }
        }
    }
}
