package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Tab groups persist by tab ID, with color, note and collapsed state. URLs are legacy migration only. */
class TabGroupManager(context: Context) {
    data class Group(
        val id: String,
        var name: String,
        var color: Int,
        val tabIds: MutableList<Int> = mutableListOf(),
        val legacyUrls: MutableList<String> = mutableListOf(),
        var taskNote: String = "",
        var collapsed: Boolean = false
    )

    private val prefs = context.getSharedPreferences("phormi_tab_groups", Context.MODE_PRIVATE)
    private val key = "groups_v2"

    fun list(): MutableList<Group> {
        val out = mutableListOf<Group>()
        val raw = prefs.getString(key, null) ?: prefs.getString("groups_v1", "[]") ?: "[]"
        runCatching {
            val a = JSONArray(raw)
            val seen = mutableSetOf<String>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                var id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
                if (!seen.add(id)) id = UUID.randomUUID().toString().also { seen.add(it) }
                val g = Group(id, o.optString("name", "Group ${i + 1}").ifBlank { "Group ${i + 1}" },
                    o.optInt("color", i % COLOR_COUNT).coerceIn(0, COLOR_COUNT - 1),
                    taskNote = o.optString("taskNote", ""), collapsed = o.optBoolean("collapsed", false))
                o.optJSONArray("tabIds")?.let { ids ->
                    for (j in 0 until ids.length()) ids.optInt(j, 0).takeIf { it > 0 && it !in g.tabIds }?.let(g.tabIds::add)
                }
                o.optJSONArray("urls")?.let { urls ->
                    for (j in 0 until urls.length()) urls.optString(j).takeIf { it.isNotBlank() && it !in g.legacyUrls }?.let(g.legacyUrls::add)
                }
                out += g
            }
        }
        return out
    }

    fun create(name: String, color: Int? = null): Group {
        val l = list()
        val g = Group(UUID.randomUUID().toString(), name.trim().ifBlank { "New group" }, (color ?: l.size % COLOR_COUNT).coerceIn(0, COLOR_COUNT - 1))
        l += g; save(l); return g
    }
    fun rename(id: String, name: String) { list().also { it.firstOrNull { g -> g.id == id }?.name = name.trim().ifBlank { "Group" }; save(it) } }
    fun setTaskNote(id: String, note: String) { list().also { it.firstOrNull { g -> g.id == id }?.taskNote = note.trim(); save(it) } }
    fun setColor(id: String, color: Int) { list().also { it.firstOrNull { g -> g.id == id }?.color = color.coerceIn(0, COLOR_COUNT - 1); save(it) } }
    fun setCollapsed(id: String, collapsed: Boolean) { list().also { it.firstOrNull { g -> g.id == id }?.collapsed = collapsed; save(it) } }
    fun delete(id: String) { save(list().filterNot { it.id == id }) }
    fun move(id: String, delta: Int) {
        val l = list(); val index = l.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, l.lastIndex)
        if (target == index) return
        val item = l.removeAt(index); l.add(target, item); save(l)
    }

    fun assignTab(groupId: String, tabId: Int, url: String = "") {
        if (tabId <= 0) return
        val groups = list(); groups.forEach { it.tabIds.remove(tabId) }
        groups.firstOrNull { it.id == groupId }?.apply {
            if (tabId !in tabIds) tabIds.add(tabId)
            if (url.isNotBlank() && url !in legacyUrls) legacyUrls.add(url)
        }
        save(groups)
    }
    fun assignTabs(groupId: String, tabIds: Collection<Int>) {
        val ids = tabIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return
        val groups = list()
        groups.forEach { it.tabIds.removeAll(ids.toSet()) }
        groups.firstOrNull { it.id == groupId }?.let { group -> ids.forEach { if (it !in group.tabIds) group.tabIds.add(it) } }
        save(groups)
    }
    fun replaceMembers(groupId: String, tabIds: Collection<Int>) {
        val ids = tabIds.filter { it > 0 }.distinct().toMutableList()
        val groups = list()
        groups.forEach { if (it.id != groupId) it.tabIds.removeAll(ids.toSet()) }
        groups.firstOrNull { it.id == groupId }?.let { it.tabIds.clear(); it.tabIds.addAll(ids) }
        save(groups)
    }
    fun unassignTab(tabId: Int) { val groups = list(); groups.forEach { it.tabIds.remove(tabId) }; save(groups) }
    fun unassignTabs(tabIds: Collection<Int>) { val ids = tabIds.toSet(); if (ids.isEmpty()) return; val groups = list(); groups.forEach { it.tabIds.removeAll(ids) }; save(groups) }
    fun prune(knownTabIds: Collection<Int>) {
        val known = knownTabIds.toSet(); val groups = list(); var changed = false
        groups.forEach { if (it.tabIds.removeAll { id -> id !in known }) changed = true }
        if (changed) save(groups)
    }
    fun groupForTab(tabId: Int): Group? = list().firstOrNull { it.tabIds.contains(tabId) }

    fun assign(id: String, url: String) { val l = list(); val g = l.firstOrNull { it.id == id } ?: return; g.legacyUrls.remove(url); g.legacyUrls.add(url); save(l) }
    fun move(oldUrl: String, newUrl: String) { val l = list(); l.forEach { if (it.legacyUrls.remove(oldUrl)) it.legacyUrls.add(newUrl) }; save(l) }
    fun unassign(url: String) { val l = list(); l.forEach { it.legacyUrls.remove(url) }; save(l) }
    fun groupFor(url: String): Group? = list().firstOrNull { it.legacyUrls.contains(url.trim()) }
    fun groupForId(id: String): Group? = list().firstOrNull { it.id == id }

    private fun save(groups: List<Group>) {
        val a = JSONArray()
        groups.forEach { g -> a.put(JSONObject().apply {
            put("id", g.id); put("name", g.name); put("color", g.color.coerceIn(0, COLOR_COUNT - 1))
            put("taskNote", g.taskNote); put("collapsed", g.collapsed)
            put("tabIds", JSONArray(g.tabIds.distinct())); put("urls", JSONArray(g.legacyUrls.distinct()))
        }) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    companion object { const val COLOR_COUNT = 6 }
}
