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
import android.widget.Toast
import org.json.JSONArray
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
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
    private var syncingSwitches = false
    private var selectedTarget = ""
    private lateinit var targetButton: Button

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
            connectCentralHubFromUi()
        }

        active.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (checked && controller.isCentralHubActive()) {
                syncingSwitches = true
                controller.setCentralHubActive(false)
                centralHub.isChecked = false
                syncingSwitches = false
            }
            controller.setActive(checked)
            refresh()
        }
        centralHub.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (!checked) {
                controller.setCentralHubActive(false)
                refresh()
                return@setOnCheckedChangeListener
            }
            centralHub.isEnabled = false
            status.text = "Connecting to Central Hub AI…"
            lifecycleScope.launch {
                try {
                    val answer = controller.connectCentralHub()
                    syncingSwitches = true
                    centralHub.isChecked = true
                    active.isChecked = false
                    syncingSwitches = false
                    controller.setActive(false)
                    refresh()
                    status.text = "Central Hub AI connected: " + answer.take(80)
                } catch (t: Throwable) {
                    syncingSwitches = true
                    centralHub.isChecked = false
                    syncingSwitches = false
                    controller.setCentralHubActive(false)
                    status.text = "Central Hub AI connection failed: " + (t.message ?: "unknown error")
                    refresh()
                } finally {
                    centralHub.isEnabled = true
                }
            }
        }
        findViewById<Button>(R.id.btn_ai_memory_retention).setOnClickListener { showMemoryRetentionChooser() }
        findViewById<Button>(R.id.btn_web_ai).setOnClickListener { openWebAi() }
        findViewById<Button>(R.id.btn_voice).setOnClickListener { startVoiceInput() }
        // Voice is explicit only. Opening the AI page must never start speech recognition automatically.
        findViewById<Button>(R.id.btn_run).setOnClickListener { runAssistant() }
        findViewById<Button>(R.id.btn_schedule).setOnClickListener { scheduleAssistant() }
        findViewById<Button>(R.id.btn_tasks).setOnClickListener { showQueuedTasks() }
        targetButton = findViewById(R.id.btn_ai_target)
        val currentTab = intent.getIntExtra("target_tab_id", -1)
        if (currentTab > 0) selectedTarget = "tab:$currentTab"
        updateTargetLabel()
        targetButton.setOnClickListener { chooseTargetTab() }
        setupProviderControls()
        status.text = PhormiAiPendingTask.lastStatus(this).ifBlank { "Describe a browser task to get started." }
    }

    private fun connectCentralHubFromUi() {
        val button = findViewById<Button>(R.id.btn_connect_hub)
        val centralHub = findViewById<Switch>(R.id.switch_central_hub)
        button.isEnabled = false
        centralHub.isEnabled = false
        status.text = "Connecting to Central Hub AI…"
        lifecycleScope.launch {
            try {
                val answer = controller.connectCentralHub()
                syncingSwitches = true
                centralHub.isChecked = true
                active.isChecked = false
                syncingSwitches = false
                controller.setActive(false)
                refresh()
                status.text = "Central Hub AI connected: " + answer.take(80)
            } catch (t: Throwable) {
                syncingSwitches = true
                centralHub.isChecked = false
                syncingSwitches = false
                controller.setCentralHubActive(false)
                refresh()
                status.text = "Central Hub AI connection failed: " + (t.message ?: "unknown error")
            } finally {
                button.isEnabled = true
                centralHub.isEnabled = true
            }
        }
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
    private fun setupProviderControls() {
        val name = findViewById<EditText>(R.id.input_provider_name)
        val key = findViewById<EditText>(R.id.input_api_key)
        val endpoint = findViewById<EditText>(R.id.input_endpoint)
        val model = findViewById<EditText>(R.id.input_model)
        findViewById<Button>(R.id.btn_template).setOnClickListener {
            val presets = AiController.TEMPLATES
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Provider preset")
                .setItems(presets.map { it.name }.toTypedArray()) { _, which ->
                    name.setText(presets[which].name)
                    endpoint.setText(presets[which].endpoint)
                    model.setText(presets[which].model)
                }.show()
        }
        findViewById<Button>(R.id.btn_save_keys).setOnClickListener { buttonView ->
            val providerName = name.text.toString().trim()
            val apiKey = key.text.toString().trim()
            val config = controller.inferProviderConfig(providerName, endpoint.text.toString(), model.text.toString())
            if (providerName.isBlank() || apiKey.isBlank() || !config.endpoint.startsWith("https://", true)) {
                status.text = "Enter a name, API key and HTTPS endpoint (or choose a preset)."
                return@setOnClickListener
            }
            buttonView.isEnabled = false
            status.text = "Checking provider and model…"
            lifecycleScope.launch {
                try {
                    val resolved = controller.resolveProviderConfig(providerName, apiKey, config.endpoint, config.model)
                    val old = controller.listProviders().firstOrNull { it.name.equals(providerName, true) }
                    controller.upsertProvider(AiController.Provider(old?.id ?: UUID.randomUUID().toString(), providerName,
                        resolved.endpoint, resolved.model, apiKey))
                    controller.setActive(true)
                    active.isChecked = true
                    key.text.clear()
                    model.setText(resolved.model)
                    refresh()
                    status.text = "$providerName is ready. Browser tasks can now use this connection."
                } catch (e: Exception) {
                    status.text = "Connection failed: ${e.message?.take(160) ?: "check the provider details"}"
                } finally {
                    buttonView.isEnabled = true
                }
            }
        }
        findViewById<Button>(R.id.btn_manage_providers).setOnClickListener {
            val saved = controller.listProviders()
            if (saved.isEmpty()) { status.text = "No providers saved yet."; return@setOnClickListener }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Saved AI connections")
                .setItems(saved.map { "${it.name} · ${it.model}" }.toTypedArray()) { _, which ->
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Remove ${saved[which].name}?")
                        .setMessage("The saved API key will be removed from this device.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            controller.removeProvider(saved[which].id)
                            refresh()
                        }.show()
                }.show()
        }
    }

    private fun chooseTargetTab() {
        val prefs = getSharedPreferences("phormi_tabs", MODE_PRIVATE)
        val ids = runCatching { JSONArray(prefs.getString("tab_ids", "[]")) }.getOrElse { JSONArray() }
        val titles = runCatching { JSONArray(prefs.getString("tab_titles", "[]")) }.getOrElse { JSONArray() }
        val targets = mutableListOf("" to "Any open tab · AI can work across tabs")
        for (i in 0 until ids.length()) {
            val id = ids.optInt(i, -1)
            if (id > 0) targets += "tab:$id" to "Tab $id · ${titles.optString(i, "New tab").take(36)}"
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Where should AI start?")
            .setItems(targets.map { it.second }.toTypedArray()) { _, which ->
                selectedTarget = targets[which].first
                updateTargetLabel()
            }.show()
    }

    private fun updateTargetLabel() {
        targetButton.text = if (selectedTarget.isBlank()) "Target: choose from open tabs" else "Target: ${selectedTarget.replace("tab:", "tab ")} (change)"
    }

    private fun runAssistant() {
        val text = instruction.text.toString().trim()
        if (text.isBlank()) { status.text = "Type a command, or press Voice when you want to speak."; return }
        if (!controller.isActive()) { status.text = "Connect Central Hub or save an AI provider first."; return }
        val queued = PhormiAiPendingTask.enqueue(applicationContext, selectedTarget, text)
        if (queued == null) { status.text = "The task queue is full. Remove a task before adding another."; return }
        PhormiAiPendingTask.saveStatus(this, "Ready: ${text.take(80)}")
        status.text = "Browser task queued. Return to Phormi to run it."
        finish()
    }

    private fun scheduleAssistant() {
        val text = instruction.text.toString().trim()
        if (text.isBlank()) { status.text = "Write the task or update you want to check later."; return }
        if (!controller.isActive()) { status.text = "Connect an AI provider first."; return }
        val now = System.currentTimeMillis()
        val morning = Calendar.getInstance().apply {
            add(Calendar.DATE, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val choices = arrayOf("In 10 minutes", "In 1 hour", "Tomorrow at 9 am", "Choose date and time")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Remind me to run this task")
            .setMessage("Android will remind you. For privacy, browser automation runs only after you return to Phormi and approve it, not unattended in the background.")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> saveScheduled(text, now + 10 * 60_000L)
                    1 -> saveScheduled(text, now + 60 * 60_000L)
                    2 -> saveScheduled(text, morning)
                    else -> {
                        val date = Calendar.getInstance()
                        android.app.DatePickerDialog(this, { _, year, month, day ->
                            android.app.TimePickerDialog(this, { _, hour, minute ->
                                val whenAt = Calendar.getInstance().apply {
                                    set(year, month, day, hour, minute); set(Calendar.SECOND, 0)
                                }.timeInMillis
                                saveScheduled(text, whenAt)
                            }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), false).show()
                        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DATE)).show()
                    }
                }
            }.show()
    }

    private fun saveScheduled(text: String, dueAt: Long) {
        if (dueAt <= System.currentTimeMillis() + 30_000L) {
            status.text = "Choose a time at least one minute in the future."
            return
        }
        val task = PhormiAiPendingTask.enqueue(this, selectedTarget, text, dueAt)
        if (task == null) { status.text = "The task queue is full. Remove a task first."; return }
        status.text = "Reminder set for ${DateFormat.getDateTimeInstance().format(Date(dueAt))}."
        instruction.text.clear()
        refresh()
    }

    private fun showQueuedTasks() {
        val tasks = PhormiAiPendingTask.list(this)
        if (tasks.isEmpty()) { status.text = "No tasks are waiting. ${PhormiAiPendingTask.lastStatus(this)}"; return }
        val labels = tasks.map { task ->
            val whenText = if (task.scheduled) DateFormat.getDateTimeInstance().format(Date(task.dueAt)) else "Ready now"
            "$whenText · ${task.instruction.take(70)}"
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Browser AI tasks")
            .setMessage("Tap a task to cancel it. Scheduled work never runs until you approve it in the browser.")
            .setItems(labels.toTypedArray()) { _, index ->
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Cancel task?")
                    .setMessage(tasks[index].instruction.take(180))
                    .setNegativeButton("Keep", null).setPositiveButton("Cancel task") { _, _ ->
                        PhormiAiPendingTask.cancel(this, tasks[index].id)
                        refresh()
                    }.show()
            }.show()
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
        keyStatus.text = controller.keyStatusSummary() + "\n" + controller.centralHubStatus() + "\nWaiting tasks: " + PhormiAiPendingTask.pendingCount(this)
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
