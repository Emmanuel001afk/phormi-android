package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/** Phormi browser AI: configurable HTTPS providers with foreground-browser execution. */
class AiActivity : AppCompatActivity() {
    private lateinit var controller: AiController
    private lateinit var status: TextView
    private lateinit var keyStatus: TextView
    private lateinit var instruction: EditText
    private lateinit var active: Switch
    private val voiceRequest = 6201
    private val voicePermissionRequest = 6202

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai)
        controller = AiController(applicationContext)
        status = findViewById(R.id.status_log)
        keyStatus = findViewById(R.id.key_status)
        instruction = findViewById(R.id.input_instruction)
        active = findViewById(R.id.switch_ai_active)
        active.isChecked = controller.isActive()
        refresh()

        val name = findViewById<EditText>(R.id.input_provider_name)
        val key = findViewById<EditText>(R.id.input_api_key)
        val endpoint = findViewById<EditText>(R.id.input_endpoint)
        val model = findViewById<EditText>(R.id.input_model)

        findViewById<Button>(R.id.btn_template).setOnClickListener {
            val options = AiController.TEMPLATES.map { it.name }.toTypedArray()
            android.app.AlertDialog.Builder(this).setTitle("External AI provider").setItems(options) { _, which ->
                val t = AiController.TEMPLATES[which]
                name.setText(t.name)
                endpoint.setText(t.endpoint)
                model.setText(t.model)
                status.text = "${t.name} selected. Paste its API key; endpoint/model can be left as the template defaults."
            }.show()
        }

        findViewById<Button>(R.id.btn_save_keys).setOnClickListener {
            val n = name.text.toString().trim().ifBlank { "AI" }
            val k = key.text.toString().trim()
            val e = endpoint.text.toString().trim()
            val m = model.text.toString().trim()
            if (k.isBlank()) { status.text = "Paste the API key first."; return@setOnClickListener }
            val inferred = controller.inferProviderConfig(n, e, m)
            if (inferred.endpoint.isBlank()) { status.text = "Endpoint is required for a custom provider name. Choose a template or enter the provider endpoint."; return@setOnClickListener }
            val button = findViewById<Button>(R.id.btn_save_keys)
            button.isEnabled = false
            status.text = "Testing HTTPS AI connection…"
            lifecycleScope.launch {
                try {
                    val cfg = controller.resolveProviderConfig(n, k, inferred.endpoint, inferred.model)
                    controller.upsertProvider(AiController.Provider(UUID.randomUUID().toString().take(12), n, cfg.endpoint, cfg.model, k))
                    controller.setActive(true)
                    active.isChecked = true
                    key.text.clear()
                    refresh()
                    status.text = "Connected: $n · ${cfg.model}"
                } catch (t: Throwable) { status.text = "Connection failed: ${t.message ?: "unknown error"}" }
                finally { button.isEnabled = true }
            }
        }
        active.setOnCheckedChangeListener { _, checked -> controller.setActive(checked); refresh() }
        findViewById<Button>(R.id.btn_voice).setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.btn_run).setOnClickListener { runAssistant() }
        if (intent.getBooleanExtra("auto_voice", false)) window.decorView.postDelayed({ startVoiceInput() }, 350)
    }

    private fun runAssistant() {
        val text = instruction.text.toString().trim()
        if (text.isBlank()) return startVoiceInput()
        if (!controller.hasAnyKey()) { status.text = "Save an external AI connection first."; return }
        if (PhormiAccessibilityService.instance == null) {
            status.text = getString(R.string.accessibility_reminder)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        PhormiAiPendingTask.enqueue(applicationContext, "", text)
        status.text = "Returning to the browser…"; finish()
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), voicePermissionRequest)
            status.text = "Microphone permission is required for Phormi AI voice input."
            return
        }
        val recognition = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Phormi AI")
        }
        runCatching { startActivityForResult(recognition, voiceRequest) }
            .onFailure { status.text = "Voice input is unavailable on this device. Check that a speech recognition service is installed." }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == voicePermissionRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceInput()
            else status.text = "Microphone permission was denied."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == voiceRequest && resultCode == Activity.RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { instruction.setText(it); instruction.setSelection(it.length) }
    }

    private fun refresh() { keyStatus.text = controller.keyStatusSummary() }
    private fun append(line: String) { runOnUiThread { status.text = if (status.text.isBlank()) line else "${status.text}\n$line" } }
}
