package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local metadata-only store for Phormi Object Anchors. Page contents are never stored. */
object PhormiObjectAnchorStore {
    private const val PREFS = "phormi_object_anchors"
    private const val KEY = "anchors"

    data class Anchor(
        val id: String,
        val label: String,
        val url: String,
        val locator: String,
        val kind: String,
        val createdAt: Long
    )

    fun list(context: Context): List<Anchor> {
        val array = runCatching { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(Anchor(o.optString("id"), o.optString("label"), o.optString("url"), o.optString("locator"), o.optString("kind"), o.optLong("createdAt")))
            }
        }
    }

    fun add(context: Context, label: String, url: String, locator: String, kind: String): Anchor {
        val anchor = Anchor(UUID.randomUUID().toString(), label.ifBlank { "Anchored object" }, url, locator, kind, System.currentTimeMillis())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        array.put(JSONObject().put("id", anchor.id).put("label", anchor.label).put("url", anchor.url).put("locator", anchor.locator).put("kind", anchor.kind).put("createdAt", anchor.createdAt))
        prefs.edit().putString(KEY, array.toString()).apply()
        return anchor
    }

    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        val kept = JSONArray()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        prefs.edit().putString(KEY, kept.toString()).apply()
    }
}
