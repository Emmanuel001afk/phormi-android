package com.uong.phormi

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.json.JSONObject

/**
 * Phormi Draft Rescue keeps one explicitly saved, short-lived draft per app.
 * It is keyboard-local, never automatic, and never captures password/private fields.
 */
object PhormiKeyboardDraftRescue {
    private const val PREFS = "phormi_keyboard_draft_rescue"
    private const val KEY_DRAFTS = "drafts"
    private const val MAX_CHARS = 4000
    private const val MAX_APPS = 12
    private const val TTL_MS = 24L * 60L * 60L * 1000L

    data class Draft(val packageName: String, val text: String, val savedAt: Long)

    fun canUse(info: EditorInfo?): Boolean =
        info?.packageName?.isNotBlank() == true &&
            !PhormiKeyboardTextEngine.isPrivateEditor(info) &&
            (info.inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT

    fun snapshot(ic: InputConnection?, info: EditorInfo?): String {
        if (ic == null || !canUse(info)) return ""
        val before = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
        return (before + after).take(MAX_CHARS)
    }

    fun save(context: Context, info: EditorInfo?, text: String): Boolean {
        if (!canUse(info)) return false
        val clean = text.take(MAX_CHARS)
        if (clean.isBlank()) return false
        val packageName = info?.packageName ?: return false
        val now = System.currentTimeMillis()
        val root = read(context)
        root.put(packageName, JSONObject().put("text", clean).put("savedAt", now))
        trim(root)
        write(context, root)
        return true
    }

    fun find(context: Context, info: EditorInfo?): Draft? {
        if (!canUse(info)) return null
        val packageName = info?.packageName ?: return null
        val item = read(context).optJSONObject(packageName) ?: return null
        val savedAt = item.optLong("savedAt", 0L)
        val text = item.optString("text", "")
        if (text.isBlank() || System.currentTimeMillis() - savedAt > TTL_MS) {
            remove(context, packageName)
            return null
        }
        return Draft(packageName, text, savedAt)
    }

    fun restore(context: Context, ic: InputConnection?, info: EditorInfo?): Boolean {
        val draft = find(context, info) ?: return false
        if (ic == null) return false
        ic.beginBatchEdit()
        try {
            ic.commitText(draft.text, 1)
        } finally {
            ic.endBatchEdit()
        }
        remove(context, draft.packageName)
        return true
    }

    fun remove(context: Context, info: EditorInfo?): Boolean {
        val packageName = info?.packageName ?: return false
        remove(context, packageName)
        return true
    }

    private fun remove(context: Context, packageName: String) {
        val root = read(context)
        root.remove(packageName)
        write(context, root)
    }

    private fun read(context: Context): JSONObject = runCatching {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DRAFTS, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun write(context: Context, root: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DRAFTS, root.toString())
            .apply()
    }

    private fun trim(root: JSONObject) {
        val keys = root.keys().asSequence().toList()
        if (keys.size <= MAX_APPS) return
        keys.sortedBy { root.optJSONObject(it)?.optLong("savedAt", 0L) ?: 0L }
            .take(keys.size - MAX_APPS)
            .forEach(root::remove)
    }
}
