package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Tab groups contain tab identities. URLs are retained only as a legacy display/migration field. */
class TabGroupManager(context: Context) {
    data class Group(
        val id: String,
        var name: String,
        val color: Int,
        val tabIds: MutableList<Int> = mutableListOf(),
        val legacyUrls: MutableList<String> = mutableListOf(),
        var taskNote: String = ""
    )

    private val prefs = context.getSharedPreferences("phormi_tab_groups", Context.MODE_PRIVATE)
    private val key = "groups_v2"

    fun list(): MutableList<Group> {
        val out = mutableListOf<Group>()
        val raw = prefs.getString(key, null) ?: prefs.getString("groups_v1", "[]") ?: "[]"
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val g = Group(o.optString("id", UUID.randomUUID().toString()), o.optString("name", "Group ${i + 1}"), o.optInt("color", i % 6), taskNote = o.optString("taskNote", ""))
                o.optJSONArray("tabIds")?.let { ids -> for (j in 0 until ids.length()) ids.optInt(j, 0).takeIf { it > 0 }?.let(g.tabIds::add) }
                o.optJSONArray("urls")?.let { urls -> for (j in 0 until urls.length()) urls.optString(j).takeIf { it.isNotBlank() }?.let(g.legacyUrls::add) }
                out += g
            }
        }
        return out
    }

    fun create(name: String): Group { val l = list(); val g = Group(UUID.randomUUID().toString(), name.trim().ifBlank { "New group" }, l.size % 6); l += g; save(l); return g }
    fun rename(id: String, name: String) { list().also { it.firstOrNull { g -> g.id == id }?.name = name.trim().ifBlank { "Group" }; save(it) } }
    fun setTaskNote(id: String, note: String) { list().also { it.firstOrNull { g -> g.id == id }?.taskNote = note.trim(); save(it) } }
    fun delete(id: String) { save(list().filterNot { it.id == id }) }

    fun assignTab(groupId: String, tabId: Int, url: String = "") {
        if (tabId <= 0) return
        val groups = list(); groups.forEach { it.tabIds.remove(tabId) }
        groups.firstOrNull { it.id == groupId }?.apply { tabIds.add(tabId); if (url.isNotBlank() && !legacyUrls.contains(url)) legacyUrls.add(url) }
        save(groups)
    }
    fun unassignTab(tabId: Int) { val groups = list(); groups.forEach { it.tabIds.remove(tabId) }; save(groups) }
    fun groupForTab(tabId: Int): Group? = list().firstOrNull { it.tabIds.contains(tabId) }

    fun assign(id: String, url: String) { val g = groupForId(id) ?: return; g.legacyUrls.remove(url); g.legacyUrls.add(url); save(list()) }
    fun move(oldUrl: String, newUrl: String) { val l = list(); l.forEach { if (it.legacyUrls.remove(oldUrl)) it.legacyUrls.add(newUrl) }; save(l) }
    fun unassign(url: String) { val l = list(); l.forEach { it.legacyUrls.remove(url) }; save(l) }
    fun groupFor(url: String): Group? = list().firstOrNull { it.legacyUrls.contains(url.trim()) }
    fun groupForId(id: String): Group? = list().firstOrNull { it.id == id }

    private fun save(groups: List<Group>) {
        val a = JSONArray()
        groups.forEach { g -> a.put(JSONObject().apply { put("id", g.id); put("name", g.name); put("color", g.color); put("taskNote", g.taskNote); put("tabIds", JSONArray(g.tabIds.distinct())); put("urls", JSONArray(g.legacyUrls.distinct())) }) }
        prefs.edit().putString(key, a.toString()).apply()
    }
}
