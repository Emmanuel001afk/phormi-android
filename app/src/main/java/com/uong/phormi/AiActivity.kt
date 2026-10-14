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
import kotlinx.coroutines.CancellationException
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
    private var lastShownStatus = ""
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            val latest = PhormiAiPendingTask.lastStatus(applicationContext)
            if (latest.isNotBlank() && latest != lastShownStatus) {
                lastShownStatus = latest
                status.text = latest
            }
            statusHandler.postDelayed(this, 1000L)
        }
    }

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
        selectedTarget = savedInstanceState?.getString("selected_target")
            ?: BrowserSessionStore(getSharedPreferences("phormi_tabs", MODE_PRIVATE)).read()?.activeId?.let { "tab:$it" }.orEmpty()
        updateTargetLabel()
        findViewById<Button>(R.id.btn_ai_target).setOnClickListener { chooseTarget() }
        findViewById<Button>(R.id.btn_template).setOnClickListener { chooseProviderTemplate() }
        findViewById<Button>(R.id.btn_save_keys).setOnClickListener { testAndSaveProvider() }
        findViewById<Button>(R.id.btn_manage_providers).setOnClickListener { manageProviders() }
        findViewById<Button>(R.id.btn_stop_tasks).setOnClickListener {
            PhormiAiPendingTask.cancelAll(applicationContext)
            PhormiAiPendingTask.saveStatus(applicationContext, "AI tasks cancelled.")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }

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
                } catch (t: CancellationException) { throw t }
                catch (t: Exception) {
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
    }

    override fun onResume() {
        super.onResume()
        statusHandler.post(statusRefresh)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected_target", selectedTarget)
        super.onSaveInstanceState(outState)
    }

    private fun updateTargetLabel() {
        val session = BrowserSessionStore(getSharedPreferences("phormi_tabs", MODE_PRIVATE)).read()
        val target = selectedTarget.removePrefix("tab:").toIntOrNull()
        val tab = session?.tabs?.firstOrNull { it.id == target }
        findViewById<Button>(R.id.btn_ai_target).text = "Target: ${tab?.title?.take(50) ?: "current tab"} ▾"
    }

    private fun chooseTarget() {
        val tabs = BrowserSessionStore(getSharedPreferences("phormi_tabs", MODE_PRIVATE)).read()?.tabs.orEmpty()
        if (tabs.isEmpty()) { status.text = "Open a browser tab first."; return }
        AlertDialog.Builder(this).setTitle("Choose the tab AI may use")
            .setItems(tabs.map { "${it.title.take(55)}\n${it.url.take(90)}" }.toTypedArray()) { _, index ->
                selectedTarget = "tab:${tabs[index].id}"
                updateTargetLabel()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseProviderTemplate() {
        AlertDialog.Builder(this).setTitle("Provider preset")
            .setItems((AiController.TEMPLATES.map { it.name } + "Custom HTTPS provider").toTypedArray()) { _, which ->
                val template = AiController.TEMPLATES.getOrNull(which)
                findViewById<EditText>(R.id.input_provider_name).setText(template?.name.orEmpty())
                findViewById<EditText>(R.id.input_endpoint).setText(template?.endpoint.orEmpty())
                findViewById<EditText>(R.id.input_model).setText(template?.model.orEmpty())
            }.show()
    }

    private fun testAndSaveProvider() {
        val name = findViewById<EditText>(R.id.input_provider_name).text.toString().trim()
        val keyInput = findViewById<EditText>(R.id.input_api_key)
        val key = keyInput.text.toString().trim()
        val endpoint = findViewById<EditText>(R.id.input_endpoint).text.toString().trim()
        val model = findViewById<EditText>(R.id.input_model).text.toString().trim()
        if (name.isBlank() || key.isBlank()) { status.text = "Enter a provider name and API key. A custom provider also needs an HTTPS endpoint."; return }
        val button = findViewById<Button>(R.id.btn_save_keys)
        button.isEnabled = false
        status.text = "Testing the AI connection…"
        lifecycleScope.launch {
            try {
                val config = controller.resolveProviderConfig(name, key, endpoint, model)
                val existing = controller.listProviders().firstOrNull { it.name.equals(name, true) }
                controller.upsertProvider(AiController.Provider(existing?.id ?: UUID.randomUUID().toString(), name, config.endpoint, config.model, key))
                keyInput.text.clear()
                status.text = "$name connection tested and saved. Model: ${config.model}. Enable Browser AI to use it."
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status.text = error.message ?: "The AI connection test failed." }
            finally { button.isEnabled = true }
        }
    }

    private fun manageProviders() {
        val providers = controller.listProviders()
        if (providers.isEmpty()) { status.text = "No external providers are saved."; return }
        AlertDialog.Builder(this).setTitle("Saved providers — choose to remove")
            .setItems(providers.map { "${it.name} · ${it.model}" }.toTypedArray()) { _, index ->
                val provider = providers[index]
                AlertDialog.Builder(this).setTitle("Remove ${provider.name}?")
                    .setMessage("The API key will be removed from encrypted storage.")
                    .setPositiveButton("Remove") { _, _ -> controller.removeProvider(provider.id); refresh() }
                    .setNegativeButton("Keep", null).show()
            }.show()
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
            } catch (t: CancellationException) { throw t }
            catch (t: Exception) {
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
    private fun runAssistant() {
        val text = instruction.text.toString().trim()
        if (text.isBlank()) { status.text = "Type a command, or press Voice when you want to speak."; return }
        if (!controller.isActive()) { status.text = "Enable Central Hub or save an external AI connection first."; return }
        // Browser-owned WebView tasks use the browser's own tab/DOM bridge.
        // Accessibility is not required for commands executed inside Phormi.
        val queued = PhormiAiPendingTask.enqueue(applicationContext, selectedTarget, text)
        if (queued == null) {
            status.text = "The browser AI queue is full (maximum 3 tasks). Wait for a task to finish."
            return
        }
        PhormiAiPendingTask.saveStatus(applicationContext, "Command queued for the selected tab.")
        // AI opened from the nested menu must return to MainActivity, not wait in the menu.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
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
        syncingSwitches = true
        findViewById<Switch>(R.id.switch_central_hub)?.isChecked = controller.isCentralHubActive()
        active.isChecked = controller.isActive() && !controller.isCentralHubActive()
        syncingSwitches = false
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
