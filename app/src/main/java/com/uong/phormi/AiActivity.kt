package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/** Configuration + voice/text control for Phormi's main browser assistant. */
class AiActivity : AppCompatActivity() {
    private lateinit var aiController: AiController
    private lateinit var statusLog: TextView
    private lateinit var keyStatus: TextView
    private lateinit var inputInstruction: EditText
    private lateinit var activeSwitch: Switch
    private val voiceRequest = 6201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai)
        aiController = AiController(applicationContext)
        statusLog = findViewById(R.id.status_log)
        keyStatus = findViewById(R.id.key_status)
        inputInstruction = findViewById(R.id.input_instruction)
        activeSwitch = findViewById(R.id.switch_ai_active)
        activeSwitch.isChecked = aiController.isActive()

        val inputName = findViewById<EditText>(R.id.input_provider_name)
        val inputKey = findViewById<EditText>(R.id.input_api_key)
        val inputEndpoint = findViewById<EditText>(R.id.input_endpoint)
        val inputModel = findViewById<EditText>(R.id.input_model)
        val transportSpinner = findViewById<Spinner>(R.id.input_transport)

        val transports = listOf("openai-compatible", "generic-http-json", "central-hub")
        transportSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transports)
        refreshKeyStatus()

        findViewById<Button>(R.id.btn_template).setOnClickListener {
            val options = AiController.TEMPLATES.map { it.name }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("Endpoint preset (no model is selected)")
                .setItems(options) { _, which ->
                    val selected = AiController.TEMPLATES[which]
                    inputName.setText(selected.name)
                    inputEndpoint.setText(selected.endpoint)
                    statusLog.text = "Preset loaded. Enter your API key and the model ID supplied by that service."
                }
                .show()
        }

        findViewById<Button>(R.id.btn_save_keys).setOnClickListener {
            val name = inputName.text.toString().trim().ifBlank { "AI" }
            val key = inputKey.text.toString().trim()
            val endpoint = inputEndpoint.text.toString().trim()
            val model = inputModel.text.toString().trim()
            val transport = transportSpinner.selectedItem?.toString().orEmpty()

            if (key.isBlank()) {
                Toast.makeText(this, "Enter the API key", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (endpoint.isBlank()) {
                Toast.makeText(this, "Enter the API endpoint (or choose a preset)", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (transport == "openai-compatible" && model.isBlank()) {
                Toast.makeText(this, "Enter the model ID for an OpenAI-compatible API", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }

            val saveButton = findViewById<Button>(R.id.btn_save_keys)
            saveButton.isEnabled = false
            statusLog.text = "Checking the configured AI connection…"
            lifecycleScope.launch {
                try {
                    val cfg = aiController.resolveProviderConfig(name, key, endpoint, model, transport)
                    aiController.upsertProvider(
                        AiController.Provider(
                            id = UUID.randomUUID().toString().take(12),
                            name = name,
                            apiKey = key,
                            endpoint = cfg.endpoint,
                            model = cfg.model,
                            transport = cfg.transport
                        )
                    )
                    aiController.setActive(true)
                    activeSwitch.isChecked = true
                    inputKey.text.clear()
                    refreshKeyStatus()
                    appendStatus("Saved $name · $transport · model ${if (cfg.model.isBlank()) "service-selected" else cfg.model}")
                    Toast.makeText(this@AiActivity, "AI connection saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    appendStatus("AI setup failed: ${e.message ?: "unable to save connection"}")
                } finally { saveButton.isEnabled = true }
            }
        }

        activeSwitch.setOnCheckedChangeListener { _, checked ->
            aiController.setActive(checked)
            appendStatus(if (checked) "AI assistant active" else "AI assistant deactivated")
            refreshKeyStatus()
        }

        findViewById<Button>(R.id.btn_voice).setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.btn_run).setOnClickListener { runAssistant() }

        if (intent.getBooleanExtra("auto_voice", false)) {
            window.decorView.postDelayed({ startVoiceInput() }, 350)
        }
    }

    private fun runAssistant() {
        val instruction = inputInstruction.text.toString().trim()
        if (instruction.isBlank()) { startVoiceInput(); return }
        if (!aiController.hasAnyKey()) { Toast.makeText(this, "Save an AI connection first", Toast.LENGTH_LONG).show(); return }
        if (!aiController.isActive()) { activeSwitch.isChecked = true; aiController.setActive(true) }
        if (PhormiAccessibilityService.instance == null) {
            appendStatus(getString(R.string.accessibility_reminder))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        findViewById<Button>(R.id.btn_run).isEnabled = false
        statusLog.text = "Assistant working…"
        lifecycleScope.launch {
            try { aiController.runTask(instruction) { appendStatus(it) } }
            finally { runOnUiThread { findViewById<Button>(R.id.btn_run).isEnabled = true } }
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to your Phormi assistant")
        }
        try { startActivityForResult(intent, voiceRequest) }
        catch (_: Exception) { Toast.makeText(this, "Voice input is not available on this device", Toast.LENGTH_LONG).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == voiceRequest && resultCode == Activity.RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) { inputInstruction.setText(text); inputInstruction.setSelection(text.length) }
        }
    }

    override fun onResume() { super.onResume(); if (::aiController.isInitialized) { activeSwitch.isChecked = aiController.isActive(); refreshKeyStatus() } }
    private fun refreshKeyStatus() { keyStatus.text = aiController.keyStatusSummary() }
    private fun appendStatus(line: String) { runOnUiThread { statusLog.append(if (statusLog.text.isBlank()) line else "\n$line") } }
}
