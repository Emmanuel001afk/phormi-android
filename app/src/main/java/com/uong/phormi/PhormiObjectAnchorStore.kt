package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persists only metadata needed to return to a page object; page content is never copied. */
object PhormiObjectAnchorStore {
    private const val PREFS = "phormi_object_anchors"
    private const val KEY = "anchors"

    data class Anchor(
        val id: String,
        val label: String,
        val url: String,
        val locator: String,
        val kind: String,
        val createdAt: Long,
        val profileName: String = PhormiEnvironmentManager.DEFAULT_ENVIRONMENT
    )

    fun list(context: Context): List<Anchor> = read(context)

    fun add(context: Context, label: String, url: String, locator: String, kind: String, profileName: String = PhormiEnvironmentManager.DEFAULT_ENVIRONMENT): Anchor {
        require(url.startsWith("http", true)) { "Anchor URL must be an HTTP(S) page" }
        require(locator.isNotBlank()) { "Anchor locator is required" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = read(context).toMutableList()
        val existing = current.firstOrNull { it.url == url && it.locator == locator }
        if (existing != null) return existing
        val anchor = Anchor(
            UUID.randomUUID().toString(),
            label.trim().ifBlank { "Anchored ${kind.ifBlank { "object" }}" }.take(160),
            url,
            locator,
            kind.ifBlank { "object" },
            System.currentTimeMillis(),
            PhormiEnvironmentManager.normalize(profileName)
        )
        current += anchor
        write(prefs, current.takeLast(200))
        return anchor
    }

    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        write(prefs, read(context).filterNot { it.id == id })
    }

    private fun read(context: Context): List<Anchor> {
        val array = runCatching {
            JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { continue }
                val url = o.optString("url")
                val locator = o.optString("locator")
                if (!url.startsWith("http", true) || locator.isBlank()) continue
                add(Anchor(id, o.optString("label", "Anchored object"), url, locator, o.optString("kind", "object"), o.optLong("createdAt", 0L), PhormiEnvironmentManager.normalize(o.optString("profileName"))))
            }
        }.sortedByDescending { it.createdAt }
    }

    private fun write(prefs: android.content.SharedPreferences, anchors: List<Anchor>) {
        val array = JSONArray()
        anchors.forEach { a ->
            array.put(JSONObject().put("id", a.id).put("label", a.label).put("url", a.url).put("locator", a.locator).put("kind", a.kind).put("createdAt", a.createdAt).put("profileName", a.profileName))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
