package com.uong.phormi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * AiActivity
 *
 * The screen where the user pastes their AI provider API keys and types
 * a plain-English instruction for the AI to carry out on their behalf,
 * using PhormiAccessibilityService as the hands and AiController as the
 * decision-maker.
 */
class AiActivity : AppCompatActivity() {

    private lateinit var aiController: AiController
    private lateinit var statusLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai)

        aiController = AiController(applicationContext)
        statusLog = findViewById(R.id.status_log)

        val inputGroq = findViewById<EditText>(R.id.input_key_groq)
        val inputDeepseek = findViewById<EditText>(R.id.input_key_deepseek)
        val inputOpenai = findViewById<EditText>(R.id.input_key_openai)
        val inputGemini = findViewById<EditText>(R.id.input_key_gemini)
        val instructionInput = findViewById<EditText>(R.id.input_instruction)

        findViewById<Button>(R.id.btn_save_keys).setOnClickListener {
            if (inputGroq.text.isNotBlank()) aiController.saveApiKey("groq", inputGroq.text.toString().trim())
            if (inputDeepseek.text.isNotBlank()) aiController.saveApiKey("deepseek", inputDeepseek.text.toString().trim())
            if (inputOpenai.text.isNotBlank()) aiController.saveApiKey("openai", inputOpenai.text.toString().trim())
            if (inputGemini.text.isNotBlank()) aiController.saveApiKey("gemini", inputGemini.text.toString().trim())

            inputGroq.text.clear()
            inputDeepseek.text.clear()
            inputOpenai.text.clear()
            inputGemini.text.clear()

            Toast.makeText(this, getString(R.string.keys_saved), Toast.LENGTH_SHORT).show()
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

            statusLog.text = ""
            lifecycleScope.launch {
                aiController.runTask(instruction) { update ->
                    appendStatus(update)
                }
            }
        }
    }

    private fun appendStatus(line: String) {
        runOnUiThread {
            statusLog.append(if (statusLog.text.isBlank()) line else "\n$line")
        }
    }
}
