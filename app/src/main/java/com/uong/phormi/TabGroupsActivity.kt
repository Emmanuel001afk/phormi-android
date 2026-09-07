package com.uong.phormi

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Real tab-group manager: groups persist by tab ID and expose their current members. */
class TabGroupsActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tab_groups)
        list = findViewById(R.id.group_list)
        findViewById<TextView>(R.id.btn_groups_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_group_new).setOnClickListener { createGroup() }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) render()
    }

    private fun render() {
        list.removeAllViews()
        val groups = TabGroupManager(this).list()
        if (groups.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No groups yet. Create a group, then assign tabs from Tab overview."
                setTextColor(0xFF94A3B8.toInt())
                setPadding(18, 30, 18, 30)
            })
            return
        }

        val tabData = readCurrentTabs()
        groups.forEach { group ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
                setBackgroundColor(0xFF172033.toInt())
            }
            row.addView(TextView(this).apply {
                text = group.name
                textSize = 17f
                setTextColor(0xFFF8FAFC.toInt())
            })
            row.addView(TextView(this).apply {
                text = "${group.tabIds.size} tabs${if (group.taskNote.isNotBlank()) " · ${group.taskNote}" else ""}"
                textSize = 12f
                setTextColor(0xFF94A3B8.toInt())
                setPadding(0, 4, 0, 8)
            })

            group.tabIds.forEach { tabId ->
                val tab = tabData.firstOrNull { it.first == tabId }
                row.addView(TextView(this).apply {
                    text = if (tab != null) "• ${tab.second}\n  ${tab.third}" else "• Tab $tabId (closed)"
                    textSize = 12f
                    setTextColor(0xFFE2E8F0.toInt())
                    setPadding(4, 5, 4, 5)
                    if (tab != null) {
                        setOnClickListener {
                            val i = Intent(this@TabGroupsActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            startActivity(i)
                            finish()
                        }
                    }
                })
            }
            if (group.tabIds.isEmpty()) {
                row.addView(TextView(this).apply {
                    text = "No tabs assigned yet."
                    textSize = 12f
                    setTextColor(0xFF64748B.toInt())
                })
            }
            row.setOnClickListener { editGroup(group.id) }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
        }
    }

    private fun readCurrentTabs(): List<Triple<Int, String, String>> {
        val prefs = getSharedPreferences("phormi_tabs", MODE_PRIVATE)
        val ids = runCatching { org.json.JSONArray(prefs.getString("tab_ids", "[]")) }.getOrElse { org.json.JSONArray() }
        val titles = runCatching { org.json.JSONArray(prefs.getString("tab_titles", "[]")) }.getOrElse { org.json.JSONArray() }
        val urls = runCatching { org.json.JSONArray(prefs.getString("tab_urls", "[]")) }.getOrElse { org.json.JSONArray() }
        return buildList {
            for (i in 0 until ids.length()) {
                val id = ids.optInt(i, 0)
                if (id > 0) add(Triple(id, titles.optString(i, "Tab $id").ifBlank { "Tab $id" }, urls.optString(i, "about:blank")))
            }
        }
    }

    private fun createGroup() {
        val input = EditText(this).apply { hint = "Group name"; setSingleLine(true) }
        AlertDialog.Builder(this)
            .setTitle("New tab group")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                TabGroupManager(this).create(input.text.toString())
                render()
            }.show()
    }

    private fun editGroup(id: String) {
        val g = TabGroupManager(this).list().firstOrNull { it.id == id } ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 0, 16, 0) }
        val name = EditText(this).apply { setText(g.name); setSingleLine(true) }
        val note = EditText(this).apply { setText(g.taskNote); hint = "Task note" }
        box.addView(name)
        box.addView(note)
        AlertDialog.Builder(this)
            .setTitle("Edit group")
            .setView(box)
            .setNegativeButton("Delete") { _, _ -> TabGroupManager(this).delete(id); render() }
            .setPositiveButton("Save") { _, _ ->
                TabGroupManager(this).rename(id, name.text.toString())
                TabGroupManager(this).setTaskNote(id, note.text.toString())
                render()
            }.show()
    }
}
