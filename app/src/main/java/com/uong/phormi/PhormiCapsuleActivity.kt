package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/**
 * Phormi's signature portable browsing-state feature.
 * Captures a useful browsing moment as a small, shareable JSON capsule.
 */
class PhormiCapsuleActivity : AppCompatActivity() {
    private lateinit var listHost: LinearLayout
    private lateinit var noteInput: EditText
    private var pendingExport: PhormiCapsuleStore.Capsule? = null
    private var pendingImportCapsule: PhormiCapsuleStore.Capsule? = null

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val capsule = pendingExport ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(PhormiCapsuleStore.exportJson(capsule).toByteArray()) }
        }.onSuccess { Toast.makeText(this, "Capsule exported", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show() }
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.let { json ->
            val capsule = PhormiCapsuleStore.importJson(this, json)
            if (capsule == null) {
                Toast.makeText(this, "That file is not a valid Phormi Capsule", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Capsule imported", Toast.LENGTH_SHORT).show()
                renderCapsules()
            }
        } ?: Toast.makeText(this, "Could not read capsule", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        renderCapsules()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 18, 32))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = "Phormi Capsule"
            setTextColor(Color.rgb(248, 250, 252))
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "Freeze a browsing moment into a portable, privacy-safe package. URLs, titles and your note travel with it — cookies, passwords and page contents do not."
            setTextColor(Color.rgb(148, 163, 184))
            textSize = 14f
            setPadding(0, dp(6), 0, dp(12))
        })

        noteInput = EditText(this).apply {
            hint = "Optional: what are you doing here?"
            setSingleLine(false)
            minLines = 2
            maxLines = 4
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(100, 116, 139))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(23, 32, 51))
        }
        root.addView(noteInput, LinearLayout.LayoutParams(-1, dp(78)).apply { bottomMargin = dp(10) })

        val capture = actionButton("Capture this moment") { capture() }
        val exportImport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        exportImport.addView(actionButton("Import", false) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        exportImport.addView(actionButton("Refresh", false) { renderCapsules() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4) })
        root.addView(capture, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        root.addView(exportImport, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(14) })

        root.addView(TextView(this).apply {
            text = "Saved Capsules"
            setTextColor(Color.rgb(226, 232, 240))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        val scroll = ScrollView(this)
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listHost)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun capture() {
        val capsule = PhormiCapsuleStore.capture(this, noteInput.text.toString())
        if (capsule == null) {
            Toast.makeText(this, "Open at least one normal web page first", Toast.LENGTH_LONG).show()
            return
        }
        noteInput.setText("")
        renderCapsules()
        Toast.makeText(this, "Capsule captured — ${capsule.tabs.size} tab(s)", Toast.LENGTH_SHORT).show()
    }

    private fun renderCapsules() {
        if (!::listHost.isInitialized) return
        listHost.removeAllViews()
        val capsules = PhormiCapsuleStore.list(this)
        if (capsules.isEmpty()) {
            listHost.addView(TextView(this).apply {
                text = "No capsules yet. Capture a research trail, shopping comparison, project setup, or anything you want to resume later."
                setTextColor(Color.rgb(100, 116, 139))
                textSize = 14f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        capsules.forEach { capsule ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(Color.rgb(17, 24, 39))
            }
            val heading = TextView(this).apply {
                text = capsule.note.ifBlank { "Untitled browsing moment" }
                setTextColor(Color.rgb(248, 250, 252))
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val details = TextView(this).apply {
                text = "${capsule.tabs.size} tab(s)  •  ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(capsule.createdAt))}"
                setTextColor(Color.rgb(148, 163, 184))
                textSize = 12f
                setPadding(0, dp(4), 0, dp(8))
            }
            val first = TextView(this).apply {
                text = capsule.tabs.first().title.ifBlank { capsule.tabs.first().url }
                setTextColor(Color.rgb(125, 211, 252))
                textSize = 13f
                maxLines = 2
            }
            card.addView(heading)
            card.addView(details)
            card.addView(first)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            actions.addView(actionButton("Open", false) { openCapsule(capsule) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
            actions.addView(actionButton("Export", false) { pendingExport = capsule; exportLauncher.launch(PhormiCapsuleStore.suggestedFileName(capsule)) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
            actions.addView(actionButton("Delete", false) { PhormiCapsuleStore.delete(this, capsule); renderCapsules() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
            card.addView(actions)
            listHost.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun openCapsule(capsule: PhormiCapsuleStore.Capsule) {
        val urls = ArrayList(capsule.tabs.map { it.url })
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putStringArrayListExtra("capsule_urls", urls)
            putExtra("capsule_active_index", capsule.activeIndex)
        })
        finish()
    }

    private fun actionButton(label: String, primary: Boolean = true, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(if (primary) Color.WHITE else Color.rgb(226, 232, 240))
        setBackgroundColor(if (primary) Color.rgb(14, 165, 233) else Color.rgb(30, 41, 59))
        minHeight = 0
        minimumHeight = 0
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
