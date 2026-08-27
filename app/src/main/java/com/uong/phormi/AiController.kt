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
 * AiController
 *
 * Takes a plain-English instruction from the user (e.g. "log into GitHub
 * and check notifications"), reads the current screen via
 * PhormiAccessibilityService, asks an AI model what single action to take
 * next, performs that action, then repeats — one step at a time — until
 * the AI reports the task is done or a step limit is hit.
 *
 * Supports multiple AI providers with fallback: if one fails or times
 * out, the next configured provider is tried automatically.
 */
class AiController(private val context: Context) {

    companion object {
        private const val TAG = "AiController"
        private const val PREFS_NAME = "phormi_ai_prefs"
        private const val MAX_STEPS = 25
        private const val REQUEST_TIMEOUT_SECONDS = 15L
    }

    data class Provider(
        val name: String,
        val apiKeyPrefKey: String,
        val endpoint: String,
        val model: String
    )

    // Order = fallback order. If the first provider with a saved key
    // fails, the next one is tried.
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

    fun saveApiKey(providerName: String, key: String) {
        prefs.edit().putString("key_$providerName", key).apply()
    }

    fun hasAnyKey(): Boolean =
        providers.any { !prefs.getString(it.apiKeyPrefKey, null).isNullOrBlank() }

    /**
     * Runs one full task, step by step, until the AI says it's finished
     * or MAX_STEPS is reached. onStatus is called after every step with
     * a short human-readable update, useful for showing progress in the UI.
     */
    suspend fun runTask(instruction: String, onStatus: (String) -> Unit) {
        val service = PhormiAccessibilityService.instance
        if (service == null) {
            onStatus("Accessibility service isn't turned on yet — enable Phormi under phone Settings > Accessibility first.")
            return
        }

        var stepsTaken = 0
        var done = false

        while (!done && stepsTaken < MAX_STEPS) {
            stepsTaken++
            val screenJson = service.readScreen()
            val decision = askAiForNextAction(instruction, screenJson)

            if (decision == null) {
                onStatus("Couldn't get a response from any AI provider — check that at least one API key is saved.")
                return
            }

            when (decision.optString("action")) {
                "tap" -> {
                    val x = decision.optInt("x", -1)
                    val y = decision.optInt("y", -1)
                    if (x >= 0 && y >= 0) {
                        service.tapAt(x, y)
                        onStatus("Step $stepsTaken: tapped ($x, $y)")
                    }
                }
                "type" -> {
                    val text = decision.optString("text", "")
                    service.typeIntoFocusedField(text)
                    onStatus("Step $stepsTaken: typed \"$text\"")
                }
                "done" -> {
                    done = true
                    onStatus("Task finished: ${decision.optString("summary", "done")}")
                }
                "stuck" -> {
                    done = true
                    onStatus("AI couldn't continue: ${decision.optString("summary", "stuck")}")
                }
                else -> {
                    onStatus("Step $stepsTaken: no clear action, stopping.")
                    done = true
                }
            }
        }

        if (!done) {
            onStatus("Stopped after $MAX_STEPS steps to avoid an endless loop.")
        }
    }

    /**
     * Sends the instruction + current screen content to each configured
     * AI provider in order, returning the first successful structured
     * decision. Falls through to the next provider on any failure.
     */
    private suspend fun askAiForNextAction(instruction: String, screenJson: String): JSONObject? {
        for (provider in providers) {
            val apiKey = prefs.getString(provider.apiKeyPrefKey, null)
            if (apiKey.isNullOrBlank()) continue

            try {
                val result = callProvider(provider, apiKey, instruction, screenJson)
                if (result != null) return result
            } catch (e: Exception) {
                Log.w(TAG, "${provider.name} failed, trying next provider: ${e.message}")
            }
        }
        return null
    }

    private suspend fun callProvider(
        provider: Provider,
        apiKey: String,
        instruction: String,
        screenJson: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val systemPrompt = """
            You control a phone screen one step at a time. You will be given the
            user's goal and a JSON list of visible screen elements (text, whether
            clickable/editable, and their center x/y coordinates).

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
                "Goal: $instruction\n\nVisible screen elements:\n$screenJson"
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

            // Some models wrap JSON in markdown fences — strip those if present.
            val cleaned = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            JSONObject(cleaned)
        }
    }
}
