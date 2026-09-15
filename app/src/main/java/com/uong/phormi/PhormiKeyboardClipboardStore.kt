package com.uong.phormi

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

/** Local clipboard history. Text and copied image/URI entries are supported; sensitive content is excluded. */
object PhormiKeyboardClipboardStore {
    data class Item(val text: String, val pinned: Boolean, val createdAt: Long, val uri: String? = null, val mime: String? = null)

    private const val PREFS = "phormi_keyboard_clipboard"
    private const val KEY_ITEMS = "items_v3"
    private const val MAX_ITEMS = 50

    @Synchronized fun list(context: Context): List<Item> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, null) ?: prefs.getString("items_v2", "[]") ?: "[]"
        return runCatching {
            val a = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val text = o.optString("text", "")
                    val uri = o.optString("uri", "").takeIf { it.isNotBlank() }
                    if (text.isNotEmpty() && !looksSensitive(text)) add(Item(text, o.optBoolean("pinned", false), o.optLong("createdAt", 0L), uri, o.optString("mime", "").takeIf { it.isNotBlank() }))
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun record(context: Context, value: CharSequence?) {
        val text = value?.toString().orEmpty()
        if (text.isEmpty() || looksSensitive(text)) return
        val existing = list(context); val old = existing.firstOrNull { it.text == text && it.uri == null }
        save(context, listOf(Item(text, old?.pinned == true, System.currentTimeMillis())) + existing.filterNot { it.text == text && it.uri == null })
    }

    @Synchronized fun recordUri(context: Context, uri: Uri, mime: String?, label: String = "Image") {
        val value = uri.toString(); if (value.isBlank()) return
        val existing = list(context); val old = existing.firstOrNull { it.uri == value }
        save(context, listOf(Item(label, old?.pinned == true, System.currentTimeMillis(), value, mime)) + existing.filterNot { it.uri == value })
    }

    @Synchronized fun togglePinned(context: Context, item: Item) {
        if (looksSensitive(item.text)) return
        val next = list(context).map { if (it.text == item.text && it.uri == item.uri) it.copy(pinned = !it.pinned) else it }
        save(context, next)
    }

    @Synchronized fun remove(context: Context, item: Item) = save(context, list(context).filterNot { it.text == item.text && it.uri == item.uri })
    @Synchronized fun clearUnpinned(context: Context) = save(context, list(context).filter { it.pinned })
    @Synchronized fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()

    /** Capture the system clipboard while the IME is active. Images are copied into app-private storage so their source permission cannot expire. */
    fun capturePrimaryClipboard(context: Context) {
        if (!shouldCaptureForActiveEditor()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0 || isSensitive(clip.description)) return
        val limit = clip.itemCount.coerceAtMost(5)
        for (index in 0 until limit) {
            val item = clip.getItemAt(index)
            val uri = item.uri
            if (uri != null) {
                val mime = runCatching { clip.description?.getMimeType(0) }.getOrNull()
                val copied = runCatching { PhormiKeyboardStickerStore.import(context, uri, "clipboard") }.getOrNull()
                if (copied != null) {
                    recordUri(context, PhormiKeyboardStickerStore.contentUri(context, copied), mime, if (mime?.startsWith("image/") == true) "🖼 Screenshot / image" else "📎 Copied content")
                } else {
                    recordUri(context, uri, mime, if (mime?.startsWith("image/") == true) "🖼 Copied image" else "📎 Copied content")
                }
            } else {
                item.coerceToText(context)?.let { record(context, it) }
            }
        }
    }

    private fun shouldCaptureForActiveEditor(): Boolean = runCatching {
        val field = PhormiKeyboardServiceV2::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val service = field.get(null) as? PhormiKeyboardServiceV2
        val info = service?.currentInputEditorInfo
        info != null && !PhormiKeyboardTextEngine.isPassword(info) && !PhormiKeyboardTextEngine.isNoPersonalizedLearning(info)
    }.getOrDefault(true)

    private fun isSensitive(description: ClipDescription?): Boolean {
        if (description == null) return false
        if (android.os.Build.VERSION.SDK_INT >= 24 && description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) return true
        return false
    }

    private fun looksSensitive(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        if (compact.length in 12..19 && compact.all(Char::isDigit)) return true
        if (text.trim().matches(Regex("\\d{4,8}"))) return true
        if (text.contains("BEGIN PRIVATE KEY") || text.contains("BEGIN RSA PRIVATE KEY") || text.contains("BEGIN OPENSSH PRIVATE KEY")) return true
        if (Regex("(?i)\\b(bearer|authorization)\\s+[A-Za-z0-9._~+/=-]{12,}").containsMatchIn(text)) return true
        return Regex("(?i)\\b(api[_-]?key|secret|access[_-]?token|refresh[_-]?token)\\s*[:=]\\s*\\S+").containsMatchIn(text)
    }

    private fun save(context: Context, source: List<Item>) {
        val safe = source.filterNot { looksSensitive(it.text) }
        val pinned = safe.filter { it.pinned }
        val unpinned = safe.filterNot { it.pinned }.take(MAX_ITEMS - pinned.size.coerceAtMost(MAX_ITEMS))
        val a = org.json.JSONArray()
        (pinned + unpinned).take(MAX_ITEMS).forEach { item ->
            a.put(org.json.JSONObject().put("text", item.text).put("pinned", item.pinned).put("createdAt", item.createdAt).apply { item.uri?.let { put("uri", it) }; item.mime?.let { put("mime", it) } })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, a.toString()).apply()
    }
}