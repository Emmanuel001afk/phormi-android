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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Unified AI gateway for Phormi.
 *
 * The browser does not bind itself to one model. A provider supplies an endpoint,
 * an API key and optionally a model ID. If the model is blank/"auto", compatible
 * providers are queried for their available text models and Phormi selects one.
 * The runtime accepts common OpenAI-compatible, Gemini-native and Anthropic APIs.
 */
class AiController(private val context: Context) {
    companion object {
        private const val TAG = "AiController"
        private const val PREFS_NAME = "phormi_ai_prefs"
        private const val KEY_PROVIDERS = "custom_providers_json"
        private const val KEY_ACTIVE = "ai_active"
        private const val MAX_STEPS = 25
        private const val REQUEST_TIMEOUT_SECONDS = 60L
        private const val MAX_HISTORY_ENTRIES = 12

        val TEMPLATES = listOf(
            Provider("grok", "Grok", "https://api.x.ai/v1/chat/completions", "", ""),
            Provider("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "", ""),
            Provider("deepseek", "DeepSeek", "https://api.deepseek.com/chat/completions", "", ""),
            Provider("openai", "OpenAI", "https://api.openai.com/v1/chat/completions", "", ""),
            Provider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "", "")
        )
    }

    data class ProviderConfig(val endpoint: String, val model: String)
    data class Provider(val id: String, val name: String, val endpoint: String, val model: String, val apiKey: String)
    private data class HistoryEntry(val stepNumber: Int, val provider: String, val actionTaken: String, val screenSummary: String)

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val history = mutableListOf<HistoryEntry>()

    fun inferProviderConfig(name: String, endpoint: String = "", model: String = ""): ProviderConfig {
        val n = name.trim().lowercase()
        val inferredEndpoint = when {
            n.contains("grok") || n.contains("xai") -> "https://api.x.ai/v1/chat/completions"
            n.contains("gemini") || n.contains("google") -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
            n.contains("groq") -> "https://api.groq.com/openai/v1/chat/completions"
            n.contains("deepseek") -> "https://api.deepseek.com/chat/completions"
            n.contains("openai") || n.contains("chatgpt") || n == "gpt" -> "https://api.openai.com/v1/chat/completions"
            n.contains("openrouter") -> "https://openrouter.ai/api/v1/chat/completions"
            n.contains("mistral") -> "https://api.mistral.ai/v1/chat/completions"
            n.contains("perplexity") -> "https://api.perplexity.ai/chat/completions"
            n.contains("together") -> "https://api.together.xyz/v1/chat/completions"
            n.contains("cerebras") -> "https://api.cerebras.ai/v1/chat/completions"
            n.contains("fireworks") -> "https://api.fireworks.ai/inference/v1/chat/completions"
            n.contains("huggingface") || n.contains("hugging face") -> "https://router.huggingface.co/v1/chat/completions"
            n.contains("cohere") -> "https://api.cohere.com/compatibility/v1/chat/completions"
            n.contains("sambanova") -> "https://api.sambanova.ai/v1/chat/completions"
            n.contains("nvidia") || n.contains("nim") -> "https://integrate.api.nvidia.com/v1/chat/completions"
            n.contains("qwen") || n.contains("dashscope") -> "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
            else -> endpoint.trim()
        }
        return ProviderConfig(inferredEndpoint, model.trim())
    }

    suspend fun resolveProviderConfig(name: String, apiKey: String): ProviderConfig =
        resolveProviderConfig(name, apiKey, "", "")

    suspend fun resolveProviderConfig(name: String, apiKey: String, endpoint: String, model: String): ProviderConfig = withContext(Dispatchers.IO) {
        val inferred = inferProviderConfig(name, endpoint, model)
        if (inferred.endpoint.isBlank()) throw IOException("AI endpoint is required")
        val explicit = inferred.model.takeIf { it.isNotBlank() && !it.equals("auto", true) }
        val selected = explicit ?: discoverModels(inferred.endpoint, apiKey).maxByOrNull(::modelScore)
            ?: throw IOException("No text model was discovered. Enter the provider's exact model ID or use a compatible /models endpoint.")
        val resolved = ProviderConfig(inferred.endpoint, selected)
        val probe = callTextProvider(Provider("probe", name, resolved.endpoint, resolved.model, apiKey), "Reply with OK.", "Reply with OK.")
        if (probe.isNullOrBlank()) throw IOException("AI provider returned an empty response")
        resolved
    }

    private fun discoverModels(endpoint: String, apiKey: String): List<String> {
        val modelsUrl = when {
            endpoint.contains("/chat/completions") -> endpoint.substringBefore("/chat/completions") + "/models"
            endpoint.endsWith("/") -> endpoint + "models"
            else -> endpoint.substringBeforeLast('/') + "/models"
        }
        return runCatching {
            val req = Request.Builder().url(modelsUrl).get()
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json").build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<String>()
                val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("data") ?: return@use emptyList<String>()
                buildList {
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id").orEmpty().trim()
                        val lower = id.lowercase()
                        if (id.isNotBlank() && !lower.contains("embedding") && !lower.contains("moderation") && !lower.contains("tts")) add(id)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun modelScore(id: String): Int {
        val n = id.lowercase()
        var score = 0
        if (n.contains("chat") || n.contains("instruct")) score += 20
        if (n.contains("latest") || n.contains("reason")) score += 10
        if (n.contains("pro") || n.contains("large")) score += 8
        if (n.contains("mini") || n.contains("small")) score += 2
        if (n.contains("embedding") || n.contains("image") || n.contains("audio") || n.contains("moderation")) score -= 1000
        return score
    }

    fun listProviders(): List<Provider> {
        val raw = prefs.getString(KEY_PROVIDERS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name", "AI")
                    val cfg = inferProviderConfig(name, o.optString("endpoint"), o.optString("model"))
                    add(Provider(o.optString("id", "p$i"), name, cfg.endpoint, cfg.model, o.optString("apiKey")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveProviders(list: List<Provider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("endpoint", p.endpoint).put("model", p.model).put("apiKey", p.apiKey))
        }
        prefs.edit().putString(KEY_PROVIDERS, arr.toString()).commit()
    }

    fun upsertProvider(provider: Provider) {
        val list = listProviders().toMutableList()
        val index = list.indexOfFirst { it.id == provider.id }
        if (index >= 0) list[index] = provider else list.add(provider)
        saveProviders(list.filter { it.apiKey.isNotBlank() && it.endpoint.isNotBlank() })
    }

    fun removeProvider(id: String) = saveProviders(listProviders().filterNot { it.id == id })
    fun hasAnyKey(): Boolean = listProviders().any { it.apiKey.isNotBlank() }
    fun isActive(): Boolean = prefs.getBoolean(KEY_ACTIVE, false) && hasAnyKey()
    fun setActive(active: Boolean) { prefs.edit().putBoolean(KEY_ACTIVE, active).apply() }
    fun keyStatusSummary(): String = listProviders().takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.name}: ready" }
        ?: "No AI providers saved yet.\nAdd a name + API key below."

    suspend fun synthesizeSearchAnswer(query: String, evidence: String): String? = withContext(Dispatchers.IO) {
        val provider = listProviders().firstOrNull { it.apiKey.isNotBlank() && it.endpoint.isNotBlank() && it.model.isNotBlank() } ?: return@withContext null
        callTextProvider(provider,
            "You are Phormi's browser search assistant. Combine the supplied evidence accurately, do not invent facts, and clearly mark uncertainty.",
            "Question: $query\n\nSearch evidence:\n$evidence"
        )
    }

    suspend fun analyzeVideo(metadata: JSONObject, frames: List<String>): String? = withContext(Dispatchers.IO) {
        val provider = listProviders().firstOrNull { it.apiKey.isNotBlank() && it.endpoint.isNotBlank() && it.model.isNotBlank() } ?: return@withContext null
        val prompt = "Analyze this browser video using only the supplied metadata and sampled frames. State visible evidence and uncertainty.\nMetadata:\n${metadata.toString(2)}"
        callTextProvider(provider, "You analyze browser video evidence without inventing unseen audio or content.", prompt)
    }

    suspend fun runTask(instruction: String, onStatus: (String) -> Unit) {
        if (!isActive()) { onStatus("AI is inactive. Save & Run an AI provider first."); return }
        val service = PhormiAccessibilityService.instance
        if (service == null) { onStatus("Enable Phormi under Settings → Accessibility, then press Run."); return }

        history.clear()
        var step = 0
        var done = false
        while (!done && step < MAX_STEPS) {
            step++
            val screen = withContext(Dispatchers.Main.immediate) { service.readScreen() }
            val result = askAiForNextAction(instruction, screen, onStatus) ?: run {
                onStatus("All configured AI providers failed.")
                return
            }
            val (decision, providerName) = result
            val action = decision.optString("action").lowercase()
            val description = when (action) {
                "tap" -> {
                    val x = decision.optInt("x", -1); val y = decision.optInt("y", -1)
                    if (x < 0 || y < 0) throw IOException("AI supplied invalid tap coordinates")
                    withContext(Dispatchers.Main.immediate) { service.tapAt(x, y) }
                    delay(450); onStatus("Step $step ($providerName): tapped ($x, $y)"); "tapped ($x, $y)"
                }
                "type" -> {
                    val text = decision.optString("text")
                    val ok = withContext(Dispatchers.Main.immediate) { service.typeIntoFocusedField(text) }
                    delay(250); onStatus("Step $step ($providerName): typed ${if (ok) "text" else "field unavailable"}"); "typed"
                }
                "scroll" -> {
                    val direction = decision.optString("direction", "down")
                    val ok = withContext(Dispatchers.Main.immediate) { service.scroll(direction) }
                    delay(350); onStatus("Step $step ($providerName): scroll $direction${if (ok) "" else " failed"}"); "scroll $direction"
                }
                "back" -> { withContext(Dispatchers.Main.immediate) { service.goBack() }; delay(350); onStatus("Step $step ($providerName): back"); "back" }
                "home" -> { withContext(Dispatchers.Main.immediate) { service.goHome() }; delay(350); onStatus("Step $step ($providerName): home"); "home" }
                "done" -> { done = true; val summary = decision.optString("summary", "done"); onStatus("Finished ($providerName): $summary"); "done: $summary" }
                "stuck" -> { done = true; val summary = decision.optString("summary", "stuck"); onStatus("Stuck ($providerName): $summary"); "stuck: $summary" }
                else -> { done = true; onStatus("Step $step ($providerName): unsupported action"); "unsupported" }
            }
            history.add(HistoryEntry(step, providerName, description, screen.take(240)))
            if (history.size > MAX_HISTORY_ENTRIES) history.removeAt(0)
        }
        if (!done) onStatus("Stopped after $MAX_STEPS steps.")
    }

    private suspend fun askAiForNextAction(instruction: String, screen: String, onStatus: (String) -> Unit): Pair<JSONObject, String>? {
        for (provider in listProviders()) {
            if (provider.apiKey.isBlank() || provider.endpoint.isBlank() || provider.model.isBlank()) continue
            try {
                val text = callTextProvider(provider,
                    "You control a phone/browser one step at a time. Reply with ONLY JSON. Supported actions: tap(x,y), type(text), scroll(direction), back, home, done(summary), stuck(summary). Never invent coordinates or elements. Sensitive password/PIN/OTP/CVV fields are unavailable.",
                    "Goal: $instruction\n\nPrevious steps:\n${history.joinToString("\n") { "${it.stepNumber}: ${it.actionTaken}" }}\n\nCurrent screen JSON:\n$screen"
                ) ?: continue
                val cleaned = text.replace("```json", "").replace("```", "").trim()
                val a = cleaned.indexOf('{'); val b = cleaned.lastIndexOf('}')
                if (a >= 0 && b > a) return JSONObject(cleaned.substring(a, b + 1)) to provider.name
                onStatus("${provider.name}: response was not an action JSON")
            } catch (e: Exception) {
                val message = e.message?.replace(Regex("\\s+"), " ")?.take(220).orEmpty()
                Log.w(TAG, "${provider.name} failed: $message")
                onStatus("${provider.name} failed: ${message.ifBlank { "request error" }}")
            }
        }
        return null
    }

    private fun callTextProvider(provider: Provider, system: String, user: String): String? {
        val endpoint = provider.endpoint.lowercase()
        val builder = Request.Builder().url(provider.endpoint)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")

        val body: JSONObject
        if (endpoint.contains("api.anthropic.com")) {
            body = JSONObject().put("model", provider.model).put("max_tokens", 1600).put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            builder.addHeader("x-api-key", provider.apiKey).addHeader("anthropic-version", "2023-06-01")
        } else if (endpoint.contains("generativelanguage.googleapis.com") && !endpoint.contains("/openai/")) {
            body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put(
                "parts", JSONArray().put(JSONObject().put("text", "$system\n\n$user")))))
            val separator = if (provider.endpoint.contains("?")) "&" else "?"
            builder.url(provider.endpoint + separator + "key=" + URLEncoder.encode(provider.apiKey, "UTF-8"))
        } else {
            body = JSONObject().put("model", provider.model).put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            builder.addHeader("Authorization", "Bearer ${provider.apiKey}")
        }

        val request = builder.post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = raw.replace(Regex("\\s+"), " ").take(260)
                throw IOException("HTTP ${response.code}${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            return extractAssistantText(raw)
        }
    }

    private fun extractAssistantText(raw: String): String? {
        val root = runCatching { JSONObject(raw) }.getOrElse { throw IOException("Provider returned invalid JSON") }
        val value: Any? = when {
            root.optJSONArray("choices")?.length()?.let { it > 0 } == true -> root.optJSONArray("choices")!!.optJSONObject(0)?.optJSONObject("message")?.opt("content")
            root.optJSONArray("candidates")?.length()?.let { it > 0 } == true -> {
                val parts = root.optJSONArray("candidates")!!.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                if (parts == null) null else buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }
            }
            root.optJSONArray("content")?.length()?.let { it > 0 } == true -> {
                val parts = root.optJSONArray("content")!!
                buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }
            }
            else -> root.optString("output_text", "").ifBlank { null }
        }
        return when (value) {
            is String -> value.trim().takeIf { it.isNotBlank() }
            is JSONArray -> buildString { for (i in 0 until value.length()) append(value.optJSONObject(i)?.optString("text").orEmpty()) }.trim().takeIf { it.isNotBlank() }
            else -> null
        }
    }
}
