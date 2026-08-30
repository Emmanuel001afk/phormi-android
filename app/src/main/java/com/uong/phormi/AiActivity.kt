package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Add any AI by name + key + endpoint + model.
 * Saved providers form a fallback chain (assist each other when one fails).
 */
class AiActivity : AppCompatActivity() {

    private lateinit var aiController: AiController
    private lateinit var statusLog: TextView
    private lateinit var keyStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai)

        aiController = AiController(applicationContext)
        statusLog = findViewById(R.id.status_log)
        keyStatus = findViewById(R.id.key_status)

        val inputName = findViewById<EditText>(R.id.input_provider_name)
        val inputKey = findViewById<EditText>(R.id.input_api_key)
        val inputEndpoint = findViewById<EditText>(R.id.input_endpoint)
        val inputModel = findViewById<EditText>(R.id.input_model)
        val instructionInput = findViewById<EditText>(R.id.input_instruction)

        refreshKeyStatus()

        findViewById<Button>(R.id.btn_save_keys).setOnClickListener {
            val name = inputName.text.toString().trim()
            val key = inputKey.text.toString().trim()
            val endpoint = inputEndpoint.text.toString().trim()
            val model = inputModel.text.toString().trim().ifBlank { "gpt-4o-mini" }
            if (name.isBlank() || key.isBlank()) {
                Toast.makeText(this, "Name and API key are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ep = endpoint.ifBlank {
                // Guess from common names
                when {
                    name.contains("groq", true) -> "https://api.groq.com/openai/v1/chat/completions"
                    name.contains("deepseek", true) -> "https://api.deepseek.com/chat/completions"
                    name.contains("gemini", true) || name.contains("google", true) ->
                        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
                    else -> "https://api.openai.com/v1/chat/completions"
                }
            }
            aiController.upsertProvider(
                AiController.Provider(
                    id = UUID.randomUUID().toString().take(8),
                    name = name,
                    endpoint = ep,
                    model = model,
                    apiKey = key
                )
            )
            inputKey.text.clear()
            refreshKeyStatus()
            Toast.makeText(this, "Saved $name", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_templates).setOnClickListener {
            val names = AiController.TEMPLATES.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Fill template")
                .setItems(names) { _, which ->
                    val t = AiController.TEMPLATES[which]
                    inputName.setText(t.name)
                    inputEndpoint.setText(t.endpoint)
                    inputModel.setText(t.model)
                }
                .show()
        }

        findViewById<Button>(R.id.btn_run).setOnClickListener {
            val instruction = instructionInput.text.toString().trim()
            if (instruction.isBlank()) {
                Toast.makeText(this, getString(R.string.enter_instruction_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!aiController.hasAnyKey()) {
                Toast.makeText(this, getString(R.string.no_api_key_saved), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (PhormiAccessibilityService.instance == null) {
                appendStatus(getString(R.string.accessibility_reminder))
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            statusLog.text = "Starting (fallback chain across your saved AIs)…"
            findViewById<Button>(R.id.btn_run).isEnabled = false
            lifecycleScope.launch {
                try {
                    aiController.runTask(instruction) { appendStatus(it) }
                } finally {
                    runOnUiThread { findViewById<Button>(R.id.btn_run).isEnabled = true }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::aiController.isInitialized) refreshKeyStatus()
    }

    private fun refreshKeyStatus() {
        keyStatus.text = aiController.keyStatusSummary()
    }

    private fun appendStatus(line: String) {
        runOnUiThread {
            statusLog.append(
                if (statusLog.text.isBlank() || statusLog.text.startsWith("Starting")) line
                else "\n$line"
            )
        }
    }
}
