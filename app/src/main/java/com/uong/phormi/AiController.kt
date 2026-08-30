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

class AiController(private val context: Context) {

    companion object {
        private const val TAG = "AiController"
        private const val PREFS_NAME = "phormi_ai_prefs"
        private const val MAX_STEPS = 25
        private const val REQUEST_TIMEOUT_SECONDS = 15L
        private const val MAX_HISTORY_ENTRIES = 12
    }

    data class Provider(
        val name: String,
        val apiKeyPrefKey: String,
        val endpoint: String,
        val model: String
    )

    private data class HistoryEntry(
        val stepNumber: Int,
        val provider: String,
        val actionTaken: String,
        val screenSummary: String
    )

    private val providers = listOf(
        Provider("groq", "key_groq", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
        Provider("deepseek", "key_deepseek", "https://api.deepseek.com/chat/completions", "deepseek-chat"),
        Provider("openai", "key_openai", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        Provider("gemini", "key_gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-1.5-flash")
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<HistoryEntry>()

    fun saveApiKey(providerName: String, key: String) {
        prefs.edit().putString("key_$providerName", key).apply()
    }

    fun hasAnyKey(): Boolean =
        providers.any { !prefs.getString(it.apiKeyPrefKey, null).isNullOrBlank() }

    suspend fun runTask(instruction: String, onStatus: (String) -> Unit) {
        val service = PhormiAccessibilityService.instance
        if (service == null) {
            onStatus("Accessibility service isn't turned on yet — enable Phormi under phone Settings > Accessibility first.")
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
                onStatus("Couldn't get a response from any AI provider — check that at least one API key is saved.")
                return
            }
            val (decision, usedProvider) = outcome

            val actionDescription = when (decision.optString("action")) {
                "tap" -> {
                    val x = decision.optInt("x", -1)
                    val y = decision.optInt("y", -1)
                    if (x >= 0 && y >= 0) {
                        service.tapAt(x, y)
                        onStatus("Step $stepsTaken ($usedProvider): tapped ($x, $y)")
                    }
                    "tapped ($x, $y)"
                }
                "type" -> {
                    val text = decision.optString("text", "")
                    service.typeIntoFocusedField(text)
                    onStatus("Step $stepsTaken ($usedProvider): typed \"$text\"")
                    "typed \"$text\""
                }
                "done" -> {
                    done = true
                    val summary = decision.optString("summary", "done")
                    onStatus("Task finished ($usedProvider): $summary")
                    "marked task done: $summary"
                }
                "stuck" -> {
                    done = true
                    val summary = decision.optString("summary", "stuck")
                    onStatus("AI couldn't continue ($usedProvider): $summary")
                    "reported stuck: $summary"
                }
                else -> {
                    done = true
                    onStatus("Step $stepsTaken ($usedProvider): no clear action, stopping.")
                    "returned no clear action"
                }
            }

            history.add(
                HistoryEntry(
                    stepNumber = stepsTaken,
                    provider = usedProvider,
                    actionTaken = actionDescription,
                    screenSummary = summarizeScreenForHistory(screenJson)
                )
            )
            if (history.size > MAX_HISTORY_ENTRIES) {
                history.removeAt(0)
            }
        }

        if (!done) {
            onStatus("Stopped after $MAX_STEPS steps to avoid an endless loop.")
        }
    }

    private suspend fun askAiForNextAction(
        instruction: String,
        screenJson: String
    ): Pair<JSONObject, String>? {
        for (provider in providers) {
            val apiKey = prefs.getString(provider.apiKeyPrefKey, null)
            if (apiKey.isNullOrBlank()) continue

            try {
                val result = callProvider(provider, apiKey, instruction, screenJson)
                if (result != null) return result to provider.name
            } catch (e: Exception) {
                Log.w(TAG, "${provider.name} failed, trying next provider: ${e.message}")
            }
        }
        return null
    }

    private fun buildHistoryText(): String {
        if (history.isEmpty()) return "(no steps taken yet)"
        return history.joinToString("\n") { entry ->
            "Step ${entry.stepNumber} [${entry.provider}] — screen: ${entry.screenSummary} → action: ${entry.actionTaken}"
        }
    }

    private fun summarizeScreenForHistory(screenJson: String): String {
        return if (screenJson.length > 200) screenJson.take(200) + "…" else screenJson
    }

    private suspend fun callProvider(
        provider: Provider,
        apiKey: String,
        instruction: String,
        screenJson: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val systemPrompt = """
            You control a phone screen one step at a time, possibly taking
            over partway through a task from a different AI provider that
            was interrupted. You will be given the user's goal, a history
            of steps already taken (which may be from a different provider),
            and a JSON list of visible screen elements (text, whether
            clickable/editable, and their center x/y coordinates).

            Use the history to avoid repeating the same action pointlessly
            and to understand what progress has already been made.

            Reply with ONLY a JSON object, no other text, in one of these shapes:
            {"action":"tap","x":123,"y":456}
            {"action":"type","text":"..."}
            {"action":"done","summary":"..."}
            {"action":"stuck","summary":"..."}
        """.trimIndent()

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        messages.put(
            JSONObject().put("role", "user").put(
                "content",
                "Goal: $instruction\n\nSteps taken so far:\n${buildHistoryText()}\n\nCurrent visible screen elements:\n$screenJson"
            )
        )

        val body = JSONObject()
        body.put("model", provider.model)
        body.put("messages", messages)
        body.put("temperature", 0)

        val request = Request.Builder()
            .url(provider.endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "${provider.name} returned HTTP ${response.code}")
                return@withContext null
            }
            val responseBody = response.body?.string() ?: return@withContext null
            val parsed = JSONObject(responseBody)
            val content = parsed
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val cleaned = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            JSONObject(cleaned)
        }
    }
}
