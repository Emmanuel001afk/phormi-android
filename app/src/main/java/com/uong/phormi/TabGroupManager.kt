package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistent tab-group state owned by Phormi. Membership is URL based so groups
 * survive MainActivity recreation and tab-id regeneration.
 */
class TabGroupManager(context: Context) {
    data class Group(
        val id: String,
        var name: String,
        var color: Int,
        val urls: MutableList<String> = mutableListOf(),
        var taskNote: String = ""
    )

    private val prefs = context.getSharedPreferences("phormi_tab_groups", Context.MODE_PRIVATE)
    private val key = "groups_v1"

    fun list(): MutableList<Group> {
        val out = mutableListOf<Group>()
        val raw = prefs.getString(key, "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val g = Group(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("name", "Group ${i + 1}"),
                    o.optInt("color", 0),
                    taskNote = o.optString("taskNote", "")
                )
                val urls = o.optJSONArray("urls")
                if (urls != null) for (j in 0 until urls.length()) urls.optString(j).takeIf { it.isNotBlank() }?.let(g.urls::add)
                out.add(g)
            }
        }
        return out
    }

    fun create(name: String): Group {
        val groups = list()
        val group = Group(UUID.randomUUID().toString(), name.trim().ifBlank { "New group" }, groups.size % 6)
        groups.add(group)
        save(groups)
        return group
    }

    fun rename(id: String, name: String) {
        val groups = list()
        groups.firstOrNull { it.id == id }?.name = name.trim().ifBlank { "Group" }
        save(groups)
    }

    fun setTaskNote(id: String, note: String) {
        val groups = list()
        groups.firstOrNull { it.id == id }?.taskNote = note.trim()
        save(groups)
    }

    fun delete(id: String) { save(list().filterNot { it.id == id }) }

    fun assign(id: String, url: String) {
        val clean = url.trim()
        if (clean.isBlank() || clean == "about:blank") return
        val groups = list()
        groups.forEach { it.urls.remove(clean) }
        groups.firstOrNull { it.id == id }?.urls?.add(clean)
        save(groups)
    }

    fun move(oldUrl: String, newUrl: String) {
        val oldClean = oldUrl.trim(); val newClean = newUrl.trim()
        if (oldClean.isBlank() || newClean.isBlank() || oldClean == newClean) return
        val groups = list()
        groups.forEach { g ->
            if (g.urls.remove(oldClean)) g.urls.add(newClean)
        }
        save(groups)
    }

    fun unassign(url: String) {
        val groups = list()
        groups.forEach { it.urls.remove(url.trim()) }
        save(groups)
    }

    fun groupFor(url: String): Group? = list().firstOrNull { it.urls.contains(url.trim()) }

    fun groupForId(id: String): Group? = list().firstOrNull { it.id == id }

    private fun save(groups: List<Group>) {
        val arr = JSONArray()
        groups.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id); put("name", g.name); put("color", g.color); put("taskNote", g.taskNote)
                put("urls", JSONArray(g.urls.distinct()))
            })
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
