package com.uong.phormi

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/** Explicit custom-emoji studio. Captured text is only sent after Generate is tapped. */
class PhormiAiEmojiActivity : androidx.appcompat.app.AppCompatActivity() {
    companion object {
        const val EXTRA_CONTEXT = "ai_emoji_context"
        const val EXTRA_CONFIG_ONLY = "ai_emoji_config_only"
    }
    private lateinit var prompt: EditText
    private lateinit var grid: GridLayout
    private val generated = mutableListOf<java.io.File>()
    private var pending = 0
    private var request: PhormiPollinationsAiEmojiEngine.Generation? = null
    private var generation = 0
    private lateinit var generationStatus: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        if (intent.getBooleanExtra(EXTRA_CONFIG_ONLY, false)) {
            showKeyDialog()
            return
        }
        intent.getStringExtra(EXTRA_CONTEXT)?.takeIf { it.isNotBlank() }?.let { prompt.setText(it) }
    }

    private fun render() {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20); setBackgroundColor(0xFF0B1220.toInt())
        }
        root.addView(TextView(this).apply { text = "Phormi AI Emoji"; textSize = 22f; setTextColor(-1) })
        root.addView(TextView(this).apply {
            text = "Choose the text for one custom reaction. Nothing is sent until you tap Generate. Images can be inserted only into editors that accept them."
            setTextColor(0xFFB7BFD4.toInt())
            setPadding(0, 8, 0, 12)
        })
        prompt = EditText(this).apply { hint = "Describe your emoji"; minLines = 2; setTextColor(-1); setHintTextColor(0xFF94A3B8.toInt()) }
        root.addView(prompt)
        prompt.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                prompt.postDelayed({
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(prompt, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, 180L)
            }
        }
        root.addView(android.widget.Button(this).apply {
            text = "Generate one emoji"
            setOnClickListener { generate() }
        })
        root.addView(android.widget.Button(this).apply {
            text = if (PhormiKeyboardPreferences.pollinationsKey(this@PhormiAiEmojiActivity).isBlank()) "Set Pollinations AI key" else "Pollinations AI key ✓"
            setOnClickListener { showKeyDialog() }
        })
        grid = GridLayout(this).apply { columnCount = 2 }
        generationStatus = android.widget.TextView(this).apply { setTextColor(android.graphics.Color.rgb(183, 191, 212)); setPadding(8, 12, 8, 12); text = "With a key: Pollinations image generation. Without a key: a simple local emoji renderer (not a generative model)." }
        root.addView(generationStatus)
        root.addView(android.widget.Button(this).apply { text = "Cancel request"; setOnClickListener { request?.cancel(); generation++; pending = 0; generationStatus.text = "Generation cancelled." } })
        root.addView(grid, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        PhormiInsets.apply(this, root)
    }

    private fun showKeyDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            hint = "pk_…"
            setSingleLine(true)
            setText(PhormiKeyboardPreferences.pollinationsKey(this@PhormiAiEmojiActivity))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pollinations AI key")
            .setMessage("Use your Pollinations publishable key. It stays in this device's private app preferences.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                PhormiKeyboardPreferences.setPollinationsKey(this@PhormiAiEmojiActivity, input.text.toString())
            }
            .setNeutralButton("Clear") { _, _ ->
                PhormiKeyboardPreferences.setPollinationsKey(this@PhormiAiEmojiActivity, "")
            }
            .show()
    }

    private fun generate() {
        val text = prompt.text.toString().trim()
        if (text.isBlank()) {
            Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show()
            return
        }
        generated.clear()
        grid.removeAllViews()
        request?.cancel()
        val id = ++generation
        pending = 1
        generationStatus.text = "Creating one emoji…"
        request = PhormiPollinationsAiEmojiEngine.generateAsync(applicationContext, text, 0) { result ->
            if (id != generation || isFinishing || isDestroyed) return@generateAsync
            pending = 0; generationStatus.text = result.status
            result.file?.let(generated::add)
            showGenerated()
        }
    }
    override fun onDestroy() { generation++; request?.cancel(); super.onDestroy() }

    private fun showGenerated() {
        grid.removeAllViews()
        generated.forEach { file ->
            val image = ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                contentDescription = "Phormi generated custom emoji"
                adjustViewBounds = true
                setPadding(8, 8, 8, 8)
                setOnClickListener { insert(file) }
                setOnLongClickListener { saveToPack(file); true }
            }
            grid.addView(image, GridLayout.LayoutParams().apply {
                width = 0; height = 230
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        Toast.makeText(this, "Tap an emoji to insert; long-press to save", Toast.LENGTH_LONG).show()
    }

    private fun insert(file: java.io.File) {
        val ok = runCatching { PhormiKeyboardServiceV2.commitPickedContent(this, PhormiKeyboardStickerStore.contentUri(this, file), intent.getStringExtra(PhormiKeyboardServiceV2.EXTRA_EDITOR_TARGET).orEmpty()) }.getOrDefault(false)
        if (!ok) Toast.makeText(this, "Saved. Return to the original keyboard and tap Insert selected image, or save it to a pack.", Toast.LENGTH_LONG).show()
        if (!intent.getStringExtra(PhormiKeyboardServiceV2.EXTRA_EDITOR_TARGET).isNullOrBlank()) finish()
    }

    private fun saveToPack(file: java.io.File) {
        val input = EditText(this).apply { hint = "Sticker pack name"; setSingleLine(true); setText("My AI Emojis") }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save to keyboard")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                PhormiKeyboardStickerPackStore.add(this, input.text.toString(), file)
                Toast.makeText(this, "Saved to your Phormi keyboard sticker pack", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
