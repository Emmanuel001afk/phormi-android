package com.uong.phormi

import android.app.Activity
import android.app.AlertDialog
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
import android.text.InputType
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
        active.isChecked = controller.isActive() && !controller.isCentralHubActive()
        val centralHub = findViewById<Switch>(R.id.switch_central_hub)
        centralHub.isChecked = controller.isCentralHubActive()
        refresh()

        findViewById<Button>(R.id.btn_connect_hub).setOnClickListener {
            val button = findViewById<Button>(R.id.btn_connect_hub)
            button.isEnabled = false
            status.text = "Connecting to Central Hub AI…"
            lifecycleScope.launch {
                try {
                    val answer = controller.connectCentralHub()
                    centralHub.isChecked = true
                    active.isChecked = false
                    refresh()
                    status.text = "Central Hub AI connected: " + answer.take(80)
                } catch (t: Throwable) {
                    status.text = "Central Hub AI connection failed: " + (t.message ?: "unknown error")
                    centralHub.isChecked = false
                } finally {
                    button.isEnabled = true
                }
            }
        }

        active.setOnCheckedChangeListener { _, checked ->
            if (checked && controller.isCentralHubActive()) {
                controller.setCentralHubActive(false)
                centralHub.isChecked = false
            }
            controller.setActive(checked)
            refresh()
        }
        centralHub.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                centralHub.isEnabled = false
                status.text = "Connecting to Central Hub AI…"
                lifecycleScope.launch {
                    try {
                        val answer = controller.connectCentralHub()
                        controller.setActive(false)
                        active.isChecked = false
                        centralHub.isChecked = true
                        refresh()
                        status.text = "Central Hub AI connected: " + answer.take(80)
                    } catch (t: Throwable) {
                        controller.setCentralHubActive(false)
                        centralHub.isChecked = false
                        refresh()
                        status.text = "Central Hub AI connection failed: " + (t.message ?: "unknown error")
                    } finally {
                        centralHub.isEnabled = true
                    }
                }
            } else {
                controller.setCentralHubActive(false)
                refresh()
            }
        }
        findViewById<Button>(R.id.btn_ai_memory_retention).setOnClickListener { showMemoryRetentionChooser() }
        findViewById<Button>(R.id.btn_web_ai).setOnClickListener { openWebAi() }
        findViewById<Button>(R.id.btn_voice).setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.btn_run).setOnClickListener { runAssistant() }
        if (intent.getBooleanExtra("auto_voice", false)) window.decorView.postDelayed({ startVoiceInput() }, 350)
    }

    private fun openWebAi() {
        val names = arrayOf("ChatGPT", "Gemini", "Grok", "Claude", "DeepSeek", "OpenRouter")
        val urls = arrayOf(
            "https://chatgpt.com/",
            "https://gemini.google.com/",
            "https://grok.com/",
            "https://claude.ai/",
            "https://chat.deepseek.com/",
            "https://openrouter.ai/chat"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Use AI on the web inside Phormi")
            .setMessage("This opens the selected provider in a normal Phormi browser tab. It is a web fallback when direct API execution is unavailable.")
            .setItems(names) { _, which ->
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("open_url", urls[which])
                })
                status.text = names[which] + " opened in Phormi."
            }.show()
    }
    private fun runAssistant() {
        val text = instruction.text.toString().trim()
        if (text.isBlank()) return startVoiceInput()
        if (!controller.isActive()) { status.text = "Enable Central Hub or save an external AI connection first."; return }
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

    private fun refresh() {
        keyStatus.text = controller.keyStatusSummary() + "\n" + controller.centralHubStatus()
        findViewById<Switch>(R.id.switch_central_hub)?.isChecked = controller.isCentralHubActive()
    }

    private fun showMemoryRetentionChooser() {
        val values = arrayOf(7L * 24L * 60L * 60L * 1000L, 30L * 24L * 60L * 60L * 1000L, 90L * 24L * 60L * 60L * 1000L, 365L * 24L * 60L * 60L * 1000L, 0L)
        val labels = arrayOf("1 week", "1 month", "3 months", "1 year", "Off / clear")
        AlertDialog.Builder(this).setTitle("AI memory retention").setItems(labels) { _, which ->
            if (which < values.lastIndex) {
                PhormiAiMemoryStore.setRetentionMs(applicationContext, values[which])
                status.text = "AI memory retention: " + labels[which]
            } else {
                PhormiAiMemoryStore.setRetentionMs(applicationContext, 0L)
                status.text = "AI memory cleared."
            }
        }.setNeutralButton("Custom days") { _, _ ->
            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "Number of days" }
            AlertDialog.Builder(this).setTitle("Custom AI memory retention").setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val days = input.text.toString().toLongOrNull()?.coerceIn(1L, 3650L)
                    if (days == null) status.text = "Enter a valid number of days."
                    else { PhormiAiMemoryStore.setRetentionMs(applicationContext, days * 24L * 60L * 60L * 1000L); status.text = "AI memory retention: " + days + " days" }
                }.setNegativeButton("Cancel", null).show()
        }.setNegativeButton("Cancel", null).show()
    }
    private fun append(line: String) { runOnUiThread { status.text = if (status.text.isBlank()) line else "${status.text}\n$line" } }
}
