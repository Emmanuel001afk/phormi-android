package com.uong.phormi

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Group manager: color, note, order, membership, open and close are all real persisted operations. */
class TabGroupsActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tab_groups)
        list = findViewById(R.id.group_list)
        summary = findViewById(R.id.group_summary)
        findViewById<TextView>(R.id.btn_groups_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_group_new).setOnClickListener { createGroup() }
        render()
        val focus = intent?.getStringExtra("group_id")
        if (focus != null && focus.isNotBlank() && intent?.getBooleanExtra("edit_group", false) == true) {
            list.post { editGroup(focus) }
        }
    }

    override fun onResume() { super.onResume(); if (::list.isInitialized) render() }

    private fun render() {
        list.removeAllViews()
        val tabData = readCurrentTabs()
        val manager = TabGroupManager(this)
        if (tabData.isNotEmpty()) manager.prune(tabData.map { it.first })
        val groups = manager.list()
        summary.text = if (groups.isEmpty()) "Create a group, then add open tabs to it."
        else "${groups.size} ${if (groups.size == 1) "group" else "groups"} · ${tabData.size} open ${if (tabData.size == 1) "tab" else "tabs"}"
        if (groups.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No groups yet. Use + to create one, or select tabs in the overview and tap Group."
                setTextColor(PhormiUiColors.secondary(this@TabGroupsActivity)); textSize = 14f
                setPadding(dp(4), dp(24), dp(4), dp(24))
            })
            return
        }
        groups.forEachIndexed { index, group ->
            val liveTabs = group.tabIds.mapNotNull { id -> tabData.firstOrNull { it.first == id } }
            val card = layoutInflater.inflate(R.layout.item_tab_group, list, false)
            card.findViewById<View>(R.id.group_color_dot).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(PhormiUiColors.groupColor(this@TabGroupsActivity, group.color))
            }
            card.findViewById<TextView>(R.id.group_name).text = group.name
            card.findViewById<TextView>(R.id.group_meta).text = PhormiTabOverview.countLabel(liveTabs.size)
            card.findViewById<TextView>(R.id.group_note).apply {
                text = group.taskNote
                visibility = if (group.taskNote.isBlank()) View.GONE else View.VISIBLE
            }
            card.findViewById<TextView>(R.id.group_hosts).text = liveTabs.map { PhormiTabOverview.shortAddress(it.third) }
                .distinct().take(4).joinToString("  ·  ").ifBlank { "No open tabs" }
            card.findViewById<TextView>(R.id.btn_group_open).apply {
                isEnabled = liveTabs.isNotEmpty()
                alpha = if (liveTabs.isNotEmpty()) 1f else 0.45f
                contentDescription = "Open group ${group.name}"
                setOnClickListener { openGroup(group.id, liveTabs.isNotEmpty()) }
            }
            card.findViewById<TextView>(R.id.btn_group_add_tabs).apply {
                contentDescription = "Add or remove tabs in ${group.name}"
                setOnClickListener { showTabPicker(group.id) }
            }
            card.findViewById<TextView>(R.id.btn_group_edit).apply {
                contentDescription = "Edit group ${group.name}"
                setOnClickListener { editGroup(group.id) }
            }
            card.setOnLongClickListener { editGroup(group.id); true }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            if (index == groups.lastIndex) card.tag = "group-card-last"
        }
    }

    private fun readCurrentTabs(): List<Triple<Int, String, String>> {
        val prefs = getSharedPreferences("phormi_tabs", MODE_PRIVATE)
        val ids = runCatching { org.json.JSONArray(prefs.getString("tab_ids", "[]")) }.getOrElse { org.json.JSONArray() }
        val titles = runCatching { org.json.JSONArray(prefs.getString("tab_titles", "[]")) }.getOrElse { org.json.JSONArray() }
        val urls = runCatching { org.json.JSONArray(prefs.getString("tab_urls", "[]")) }.getOrElse { org.json.JSONArray() }
        return buildList {
            for (i in 0 until minOf(ids.length(), urls.length())) {
                val id = ids.optInt(i, 0)
                if (id > 0) {
                    val url = urls.optString(i, PhormiTabOverview.NEW_TAB_URL)
                    add(Triple(id, PhormiTabOverview.displayTitle(titles.optString(i, "New tab"), url), url))
                }
            }
        }
    }

    private fun createGroup() {
        val input = EditText(this).apply { hint = "Group name"; setSingleLine(true); setPadding(dp(4), dp(12), dp(4), dp(12)) }
        val box = LinearLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle("New tab group").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                TabGroupManager(this).create(input.text.toString())
                render()
            }.show()
    }

    private fun showTabPicker(groupId: String) {
        val manager = TabGroupManager(this)
        val group = manager.groupForId(groupId) ?: return
        val tabs = readCurrentTabs()
        if (tabs.isEmpty()) {
            Toast.makeText(this, "There are no open tabs to add.", Toast.LENGTH_SHORT).show()
            return
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val checks = linkedMapOf<Int, CheckBox>()
        val scroller = ScrollView(this).apply { addView(content, LinearLayout.LayoutParams(-1, -2)) }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun small(label: String, click: () -> Unit) {
            tools.addView(android.widget.Button(this).apply {
                text = label; isAllCaps = false; setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        small("Select all") { checks.values.forEach { it.isChecked = true } }
        small("Clear") { checks.values.forEach { it.isChecked = false } }
        content.addView(tools, LinearLayout.LayoutParams(-1, -2))
        tabs.forEach { (id, title, url) ->
            val check = CheckBox(this).apply {
                text = "$title\n${PhormiTabOverview.shortAddress(url)}"
                isChecked = id in group.tabIds
                setPadding(0, dp(8), 0, dp(8))
            }
            checks[id] = check
            content.addView(check, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this).setTitle("Tabs in ${group.name}").setView(scroller)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                manager.replaceMembers(groupId, checks.filterValues { it.isChecked }.keys)
                render()
            }.show()
    }

    private fun editGroup(id: String) {
        val manager = TabGroupManager(this)
        val groups = manager.list()
        val index = groups.indexOfFirst { it.id == id }
        val g = groups.getOrNull(index) ?: return
        var color = g.color
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val name = EditText(this).apply { setText(g.name); hint = "Group name"; setSingleLine(true) }
        val note = EditText(this).apply { setText(g.taskNote); hint = "Task note" }
        content.addView(name, LinearLayout.LayoutParams(-1, -2))
        content.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(TextView(this).apply { text = "Color"; setTextColor(PhormiUiColors.secondary(this@TabGroupsActivity)); setPadding(0, dp(12), 0, dp(6)) })
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dots = mutableListOf<TextView>()
        fun paintDots() {
            dots.forEachIndexed { i, dot ->
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(PhormiUiColors.groupColor(this@TabGroupsActivity, i))
                    setStroke(dp(if (i == color) 3 else 1), if (i == color) PhormiUiColors.text(this@TabGroupsActivity) else PhormiUiColors.border(this@TabGroupsActivity))
                }
                dot.contentDescription = "Group color ${i + 1}"
            }
        }
        repeat(TabGroupManager.COLOR_COUNT) { i ->
            val dot = TextView(this).apply {
                isClickable = true; isFocusable = true
                setOnClickListener { color = i; paintDots() }
            }
            dots += dot
            colorRow.addView(dot, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        }
        paintDots()
        content.addView(colorRow, LinearLayout.LayoutParams(-1, -2))
        fun action(label: String, enabled: Boolean = true, click: () -> Unit) {
            content.addView(android.widget.Button(this).apply {
                text = label; isAllCaps = false; isEnabled = enabled; alpha = if (enabled) 1f else 0.45f
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        }
        action("Move up", index > 0) { manager.move(id, -1); render() }
        action("Move down", index < groups.lastIndex) { manager.move(id, 1); render() }
        action("Close group tabs") {
            AlertDialog.Builder(this).setTitle("Close all tabs in ${g.name}?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Close group") { _, _ -> closeGroup(id) }.show()
        }
        val dialog = AlertDialog.Builder(this).setTitle("Edit group").setView(content)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(this).setTitle("Delete ${g.name}?")
                    .setMessage("Tabs stay open; only the group is removed.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> manager.delete(id); render() }.show()
            }
            .setPositiveButton("Save") { _, _ ->
                manager.rename(id, name.text.toString())
                manager.setTaskNote(id, note.text.toString())
                manager.setColor(id, color)
                render()
            }.create()
        dialog.show()
    }

    private fun openGroup(groupId: String, hasTabs: Boolean) {
        if (!hasTabs) {
            Toast.makeText(this, "This group has no open tabs. Add tabs first.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("action", "open_group")
            putExtra("group_id", groupId)
        })
        finish()
    }

    private fun closeGroup(groupId: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("action", "close_group")
            putExtra("group_id", groupId)
        })
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
