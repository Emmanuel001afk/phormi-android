package com.uong.phormi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dynamic AI providers + fallback.
 *
 * User can save any number of providers: display name, API key, endpoint, model.
 * Built-in templates are only defaults for first setup.
 */
class AiController(private val context: Context) {

    companion object {
        private const val TAG = "AiController"
        private const val PREFS_NAME = "phormi_ai_prefs"
        private const val KEY_PROVIDERS = "custom_providers_json"
        private const val MAX_STEPS = 25
        private const val REQUEST_TIMEOUT_SECONDS = 45L
        private const val MAX_HISTORY_ENTRIES = 12

        val TEMPLATES = listOf(
            Provider(
                "groq",
                "Groq",
                "https://api.groq.com/openai/v1/chat/completions",
                "llama-3.3-70b-versatile",
                ""
            ),
            Provider(
                "deepseek",
                "DeepSeek",
                "https://api.deepseek.com/chat/completions",
                "deepseek-chat",
                ""
            ),
            Provider(
                "openai",
                "OpenAI",
                "https://api.openai.com/v1/chat/completions",
                "gpt-4o-mini",
                ""
            ),
            Provider(
                "gemini",
                "Gemini",
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                "gemini-1.5-flash",
                ""
            )
        )
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
                out.add(
                    Provider(
                        id = o.optString("id", "p$i"),
                        name = o.optString("name", "AI"),
                        endpoint = o.optString("endpoint", ""),
                        model = o.optString("model", "gpt-4o-mini"),
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
        val idx = list.indexOfFirst {
            it.id == provider.id || it.name.equals(provider.name, true)
        }
        if (idx >= 0) list[idx] = provider else list.add(provider)
        saveProviders(
            list.filter {
                it.apiKey.isNotBlank() && it.endpoint.isNotBlank()
            }
        )
    }

    fun removeProvider(id: String) {
        saveProviders(listProviders().filterNot { it.id == id })
    }

    fun hasAnyKey(): Boolean =
        listProviders().any { it.apiKey.isNotBlank() }

    fun keyStatusSummary(): String {
        val list = listProviders()
        if (list.isEmpty()) {
            return "No AI providers saved yet.\nAdd a name + API key below."
        }
        return list.joinToString("\n") {
            "${it.name}: ready · ${it.model}"
        }
    }

    suspend fun runTask(
        instruction: String,
        onStatus: (String) -> Unit
    ) {
        val service = PhormiAccessibilityService.instance
        if (service == null) {
            onStatus(
                "Enable Phormi under Settings → Accessibility, then press Run."
            )
            return
        }

        if (!hasAnyKey()) {
            onStatus(
                "Save at least one AI provider (name + key + endpoint) first."
            )
            return
        }

        history.clear()
        var stepsTaken = 0
        var done = false

        while (!done && stepsTaken < MAX_STEPS) {
            stepsTaken++
            val screenJson = service.readScreen()
            val outcome = askAiForNextAction(instruction, screenJson)

            if (outcome == null) {
                onStatus(
                    "All providers failed or returned empty — check keys and endpoints."
                )
                return
            }

            val (decision, usedProvider) = outcome

            val actionDescription = when (decision.optString("action")) {
                "tap" -> {
                    val x = decision.optInt("x", -1)
                    val y = decision.optInt("y", -1)
                    if (x >= 0 && y >= 0) {
                        service.tapAt(x, y)
                        onStatus(
                            "Step $stepsTaken ($usedProvider): tapped ($x, $y)"
                        )
                    }
                    "tapped ($x, $y)"
                }

                "type" -> {
                    val text = decision.optString("text", "")
                    service.typeIntoFocusedField(text)
                    onStatus(
                        "Step $stepsTaken ($usedProvider): typed \"$text\""
                    )
                    "typed \"$text\""
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
                    onStatus(
                        "Step $stepsTaken ($usedProvider): no clear action, stopping."
                    )
                    "no clear action"
                }
            }

            history.add(
                HistoryEntry(
                    stepsTaken,
                    usedProvider,
                    actionDescription,
                    summarizeScreenForHistory(screenJson)
                )
            )
            if (history.size > MAX_HISTORY_ENTRIES) history.removeAt(0)
        }

        if (!done) onStatus("Stopped after $MAX_STEPS steps.")
    }

    /**
     * Used by the unified search surface. It asks the first working configured
     * provider to synthesize one coherent answer from the merged search evidence.
     * This does not expose the provider's separate answer as a separate search result.
     */
    suspend fun synthesizeSearchAnswer(
        query: String,
        evidence: String
    ): String? {
        val providers = listProviders()
            .filter { it.apiKey.isNotBlank() && it.endpoint.isNotBlank() }

        if (providers.isEmpty()) return null

        val systemPrompt = """
            You are the answer layer of a unified web search engine.
            Use only the supplied search evidence.
            Produce one concise, coherent answer to the user's query.
            Do not mention individual AI providers.
            Do not invent facts that are not supported by the evidence.
            If the evidence is insufficient, say so briefly.
        """.trimIndent()

        val userPrompt = """
            Query:
            $query

            Search evidence:
            $evidence
        """.trimIndent()

        for (provider in providers) {
            try {
                val answer = callTextProvider(
                    provider,
                    systemPrompt,
                    userPrompt
                )
                if (!answer.isNullOrBlank()) return answer.trim()
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "${provider.name} synthesis failed: ${e.message}"
                )
            }
        }

        return null
    }

    private suspend fun askAiForNextAction(
        instruction: String,
        screenJson: String
    ): Pair<JSONObject, String>? {
        for (provider in listProviders()) {
            if (provider.apiKey.isBlank() || provider.endpoint.isBlank()) continue
            try {
                val result = callProvider(
                    provider,
                    instruction,
                    screenJson
                )
                if (result != null) return result to provider.name
            } catch (e: Exception) {
                Log.w(TAG, "${provider.name} failed: ${e.message}")
            }
        }
        return null
    }

    private fun buildHistoryText(): String {
        if (history.isEmpty()) return "(no steps yet)"
        return history.joinToString("\n") {
            "Step ${it.stepNumber} [${it.provider}] — " +
                "${it.screenSummary} → ${it.actionTaken}"
        }
    }

    private fun summarizeScreenForHistory(
        screenJson: String
    ): String =
        if (screenJson.length > 200) {
            screenJson.take(200) + "…"
        } else {
            screenJson
        }

    private suspend fun callTextProvider(
        provider: Provider,
        systemPrompt: String,
        userPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt)
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", userPrompt)
            )

        val body = JSONObject()
            .put("model", provider.model)
            .put("messages", messages)
            .put("temperature", 0.2)

        val request = Request.Builder()
            .url(provider.endpoint)
            .addHeader(
                "Authorization",
                "Bearer ${provider.apiKey}"
            )
            .addHeader("Content-Type", "application/json")
            .post(
                body.toString().toRequestBody(
                    "application/json".toMediaType()
                )
            )
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "${provider.name} HTTP ${response.code}"
                )
                return@withContext null
            }

            val responseBody =
                response.body?.string() ?: return@withContext null

            val content = JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            content
                .removePrefix("```")
                .removePrefix("text")
                .removeSuffix("```")
                .trim()
        }
    }

    private suspend fun callProvider(
        provider: Provider,
        instruction: String,
        screenJson: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val systemPrompt = """
            You control a phone screen one step at a time.
            Goal, step history, and visible UI elements (JSON) are provided.
            Reply with ONLY one JSON object:
            {"action":"tap","x":123,"y":456}
            {"action":"type","text":"..."}
            {"action":"done","summary":"..."}
            {"action":"stuck","summary":"..."}
        """.trimIndent()

        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt)
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        "Goal: $instruction\n\n" +
                            "Steps so far:\n${buildHistoryText()}\n\n" +
                            "Screen:\n$screenJson"
                    )
            )

        val body = JSONObject()
            .put("model", provider.model)
            .put("messages", messages)
            .put("temperature", 0)

        val request = Request.Builder()
            .url(provider.endpoint)
            .addHeader(
                "Authorization",
                "Bearer ${provider.apiKey}"
            )
            .addHeader("Content-Type", "application/json")
            .post(
                body.toString().toRequestBody(
                    "application/json".toMediaType()
                )
            )
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "${provider.name} HTTP ${response.code}"
                )
                return@withContext null
            }

            val responseBody =
                response.body?.string() ?: return@withContext null

            val content = JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            JSONObject(content)
        }
    }
}
