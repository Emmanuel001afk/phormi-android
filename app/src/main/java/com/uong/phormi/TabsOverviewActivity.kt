package com.uong.phormi

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Sectioned tab workspace with three genuinely different layouts and first-class group controls. */
class TabsOverviewActivity : AppCompatActivity() {
    private val allTabs = mutableListOf<PhormiTabOverview.TabEntry>()
    private var visibleTabs = emptyList<PhormiTabOverview.TabEntry>()
    private var groups = emptyList<PhormiTabOverview.GroupInfo>()
    private var activeId = -1
    private lateinit var tabsVerticalScroll: View
    private lateinit var tabsHorizontalScroll: View
    private lateinit var tabsGridScroll: View
    private lateinit var tabsVertical: LinearLayout
    private lateinit var tabsHorizontal: LinearLayout
    private lateinit var tabsGrid: LinearLayout
    private lateinit var tabCount: TextView
    private lateinit var summary: TextView
    private lateinit var selectButton: TextView
    private lateinit var selectAllButton: TextView
    private lateinit var groupSelectedButton: TextView
    private lateinit var closeSelectedButton: TextView
    private lateinit var searchInput: EditText
    private lateinit var groupFilterChips: LinearLayout
    private var mode = "vertical"
    private var query = ""
    private var groupFilter: String? = null
    private var selectionMode = false
    private val selectedIds = linkedSetOf<Int>()

    companion object { private const val PREFS = "phormi_tabs"; private const val KEY_MODE = "tab_view_mode" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs_overview)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mode = PhormiTabOverview.normalizeMode(prefs.getString(KEY_MODE, "vertical"))
        findViewById<View>(R.id.btn_close_overview).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_new_tab_overview).setOnClickListener { returnAction(Intent().putExtra("action", "new_tab")) }
        findViewById<View>(R.id.btn_split_overview).setOnClickListener { returnAction(Intent().putExtra("action", "toggle_split")) }
        findViewById<View>(R.id.btn_groups_overview).setOnClickListener { startActivity(Intent(this, TabGroupsActivity::class.java)) }
        mapOf("vertical" to R.id.btn_mode_list, "grid" to R.id.btn_mode_grid, "horizontal" to R.id.btn_mode_carousel).forEach { (value, id) ->
            findViewById<View>(id).setOnClickListener {
                mode = value
                prefs.edit().putString(KEY_MODE, mode).apply()
                applyFilter()
            }
        }
        tabsVerticalScroll = findViewById(R.id.tabs_vertical_scroll)
        tabsHorizontalScroll = findViewById(R.id.tabs_horizontal_scroll)
        tabsGridScroll = findViewById(R.id.tabs_grid_scroll)
        tabsVertical = findViewById(R.id.tabs_vertical)
        tabsHorizontal = findViewById(R.id.tabs_horizontal)
        tabsGrid = findViewById(R.id.tabs_grid)
        tabCount = findViewById(R.id.tab_count)
        summary = findViewById(R.id.overview_summary)
        selectButton = findViewById(R.id.btn_select_tabs)
        selectAllButton = findViewById(R.id.btn_select_all_tabs)
        groupSelectedButton = findViewById(R.id.btn_group_selected)
        closeSelectedButton = findViewById(R.id.btn_close_selected)
        searchInput = findViewById(R.id.search_tabs)
        groupFilterChips = findViewById(R.id.group_filter_chips)

        selectButton.setOnClickListener {
            selectionMode = !selectionMode
            if (!selectionMode) selectedIds.clear()
            updateSelectionUi(); applyFilter()
        }
        selectAllButton.setOnClickListener {
            val visibleIds = visibleTabs.map { it.id }.toSet()
            if (visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)) selectedIds.removeAll(visibleIds)
            else selectedIds.addAll(visibleIds)
            updateSelectionUi(); applyFilter()
        }
        groupSelectedButton.setOnClickListener {
            if (selectedIds.isNotEmpty()) showGroupChooser(selectedIds.toIntArray())
        }
        closeSelectedButton.setOnClickListener { confirmCloseSelected() }
        findViewById<View>(R.id.btn_clear_tab_search).setOnClickListener { searchInput.setText("") }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        loadTabs(); updateSelectionUi(); applyFilter()
    }

    override fun onResume() {
        super.onResume()
        if (::tabsVertical.isInitialized) {
            loadTabs()
            selectedIds.retainAll(allTabs.map { it.id }.toSet())
            updateSelectionUi(); applyFilter()
        }
    }

    private fun returnAction(intent: Intent) { setResult(RESULT_OK, intent); finish() }

    private fun applyFilter() {
        var items = PhormiTabOverview.filter(allTabs, query)
        groupFilter?.let { id -> items = items.filter { it.groupId == id } }
        visibleTabs = items.filterNot { tab ->
            tab.id != activeId && tab.groupId.isNotBlank() && query.isBlank() && groupFilter != tab.groupId &&
                groups.firstOrNull { it.id == tab.groupId }?.collapsed == true
        }
        findViewById<View>(R.id.btn_clear_tab_search).visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        renderGroupFilters()
        renderTabs(items)
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        selectButton.text = if (selectionMode) "Done" else "Select"
        selectButton.contentDescription = if (selectionMode) "Finish selecting tabs" else "Select multiple tabs"
        selectButton.isEnabled = allTabs.isNotEmpty()
        selectButton.alpha = if (allTabs.isEmpty()) 0.45f else 1f
        findViewById<View>(R.id.overview_layout_controls).visibility = if (selectionMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.overview_group_filters).visibility = if (selectionMode || groups.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.overview_selection_bar).visibility = if (selectionMode) View.VISIBLE else View.GONE
        val visibleIds = visibleTabs.map { it.id }.toSet()
        val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)
        selectAllButton.text = if (allVisibleSelected) "Clear all" else "Select all"
        selectAllButton.isEnabled = visibleTabs.isNotEmpty()
        findViewById<TextView>(R.id.selection_summary).text = "${selectedIds.size} selected"
        groupSelectedButton.isEnabled = selectedIds.isNotEmpty()
        groupSelectedButton.alpha = if (selectedIds.isEmpty()) 0.45f else 1f
        closeSelectedButton.text = "Close (${selectedIds.size})"
        closeSelectedButton.isEnabled = selectedIds.isNotEmpty()
        closeSelectedButton.alpha = if (selectedIds.isEmpty()) 0.45f else 1f
        val count = PhormiTabOverview.countLabel(allTabs.size)
        val filterName = groupFilter?.let { id -> groups.firstOrNull { it.id == id }?.name }
        summary.text = when {
            query.isNotBlank() -> "${visibleTabs.size} of $count match"
            filterName != null -> "$count · $filterName"
            groups.isNotEmpty() -> "$count · ${groups.size} ${if (groups.size == 1) "group" else "groups"}"
            else -> "$count · organized by section"
        }
    }

    private fun confirmCloseSelected() {
        if (selectedIds.isEmpty()) return
        val count = selectedIds.size
        AlertDialog.Builder(this)
            .setTitle("Close $count ${if (count == 1) "tab" else "tabs"}?")
            .setMessage("The selected tabs will be removed from this browser session.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Close $count") { _, _ ->
                returnAction(Intent().putExtra("action", "close_many").putExtra("tab_ids", selectedIds.toIntArray()))
            }.show()
    }

    private fun renderGroupFilters() {
        groupFilterChips.removeAllViews()
        if (groups.isEmpty()) return
        if (groupFilter != null && groups.none { it.id == groupFilter }) groupFilter = null
        fun chip(label: String, selected: Boolean, color: Int, click: () -> Unit) {
            groupFilterChips.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(if (selected) PhormiUiColors.accent(this@TabsOverviewActivity) else PhormiUiColors.text(this@TabsOverviewActivity))
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (selected) PhormiUiColors.accentSoft(this@TabsOverviewActivity) else PhormiUiColors.surface(this@TabsOverviewActivity))
                    setStroke(dp(1), if (selected) PhormiUiColors.accent(this@TabsOverviewActivity) else color)
                }
                isClickable = true; isFocusable = true
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(-2, dp(32)).apply { marginEnd = dp(6) })
        }
        chip("All tabs", groupFilter == null, PhormiUiColors.border(this)) { groupFilter = null; applyFilter() }
        groups.forEach { group ->
            val count = allTabs.count { it.groupId == group.id }
            chip("${group.name} · $count", groupFilter == group.id, PhormiUiColors.groupColor(this, group.color)) {
                groupFilter = if (groupFilter == group.id) null else group.id
                applyFilter()
            }
        }
    }

    private fun renderTabs(items: List<PhormiTabOverview.TabEntry>) {
        listOf(tabsVertical, tabsHorizontal, tabsGrid).forEach { it.removeAllViews() }
        tabCount.text = allTabs.size.toString()
        tabCount.contentDescription = PhormiTabOverview.countLabel(allTabs.size)
        mapOf("vertical" to R.id.btn_mode_list, "grid" to R.id.btn_mode_grid, "horizontal" to R.id.btn_mode_carousel).forEach { (value, id) ->
            findViewById<TextView>(id).apply {
                val selected = mode == value
                isSelected = selected
                setTextColor(if (selected) PhormiUiColors.accent(this@TabsOverviewActivity) else PhormiUiColors.secondary(this@TabsOverviewActivity))
                background = GradientDrawable().apply {
                    setColor(if (selected) PhormiUiColors.accentSoft(this@TabsOverviewActivity) else android.graphics.Color.TRANSPARENT)
                    cornerRadius = dp(12).toFloat()
                }
            }
        }
        tabsVerticalScroll.visibility = if (mode == "vertical") View.VISIBLE else View.GONE
        tabsHorizontalScroll.visibility = if (mode == "horizontal") View.VISIBLE else View.GONE
        tabsGridScroll.visibility = if (mode == "grid") View.VISIBLE else View.GONE
        val target = when (mode) { "horizontal" -> tabsHorizontal; "grid" -> tabsGrid; else -> tabsVertical }
        if (items.isEmpty()) {
            target.addView(emptyState(), LinearLayout.LayoutParams(-1, -2))
            return
        }
        val sections = PhormiTabOverview.sections(items, activeId, groups)
        if (mode == "horizontal") {
            val width = dp(PhormiTabOverview.carouselWidth(resources.configuration.screenWidthDp))
            sections.forEach { section ->
                val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                column.addView(makeSectionHeader(section))
                if (!isCollapsed(section)) {
                    section.tabs.forEach { column.addView(makeTab("horizontal", it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
                }
                target.addView(column, LinearLayout.LayoutParams(width, -2).apply { marginEnd = dp(10) })
            }
            return
        }
        val columns = PhormiTabOverview.columns(mode, resources.configuration.screenWidthDp, resources.configuration.fontScale)
        val gap = dp(if (mode == "grid") 8 else 6)
        sections.forEach { section ->
            target.addView(makeSectionHeader(section))
            if (isCollapsed(section)) return@forEach
            section.tabs.chunked(columns).forEach { members ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                members.forEachIndexed { index, item ->
                    row.addView(makeTab(mode, item), LinearLayout.LayoutParams(0, -2, 1f).apply {
                        setMargins(0, 0, if (index < members.lastIndex) gap else 0, gap)
                    })
                }
                if (members.size < columns) repeat(columns - members.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = gap }) }
                target.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun isCollapsed(section: PhormiTabOverview.Section): Boolean {
        val groupId = section.groupId ?: return false
        if (query.isNotBlank() || groupFilter == groupId) return false
        return groups.firstOrNull { it.id == groupId }?.collapsed == true
    }

    private fun emptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(40), dp(24), dp(40))
        addView(TextView(this@TabsOverviewActivity).apply {
            text = if (query.isBlank()) "A fresh start" else "No matching tabs"
            textSize = 19f; setTextColor(PhormiUiColors.text(this@TabsOverviewActivity)); gravity = Gravity.CENTER
        })
        addView(TextView(this@TabsOverviewActivity).apply {
            text = if (query.isBlank()) "Open a new tab to start browsing." else "Try a site name, address, group, or environment."
            textSize = 14f; setTextColor(PhormiUiColors.secondary(this@TabsOverviewActivity)); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun makeSectionHeader(section: PhormiTabOverview.Section): View {
        val header = LayoutInflater.from(this).inflate(R.layout.item_overview_section, tabsVertical, false)
        header.tag = section.key
        header.findViewById<TextView>(R.id.section_title).text = section.title
        header.findViewById<TextView>(R.id.section_count).text = section.tabs.size.toString()
        val group = section.groupId?.let { id -> groups.firstOrNull { it.id == id } }
        header.findViewById<View>(R.id.section_dot).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when {
                group != null -> PhormiUiColors.groupColor(this@TabsOverviewActivity, group.color)
                section.key == "current" -> PhormiUiColors.accent(this@TabsOverviewActivity)
                section.key == "ghost" -> PhormiUiColors.coralText(this@TabsOverviewActivity)
                section.key == "new" -> PhormiUiColors.border(this@TabsOverviewActivity)
                else -> PhormiUiColors.secondary(this@TabsOverviewActivity)
            })
        }
        val chevron = header.findViewById<TextView>(R.id.section_chevron)
        if (group != null) {
            val collapsed = isCollapsed(section)
            chevron.visibility = View.VISIBLE
            chevron.text = if (collapsed) "▸" else "▾"
            header.isClickable = true; header.isFocusable = true
            header.contentDescription = "${if (collapsed) "Expand" else "Collapse"} group ${section.title}"
            header.setOnClickListener {
                TabGroupManager(this).setCollapsed(group.id, !collapsed)
                loadTabs(); applyFilter()
            }
        } else {
            chevron.visibility = View.GONE
        }
        header.findViewById<TextView>(R.id.btn_open_section).apply {
            visibility = if (group != null && section.tabs.isNotEmpty()) View.VISIBLE else View.GONE
            contentDescription = "Open group ${section.title}"
            setOnClickListener { returnAction(Intent().putExtra("action", "open_group").putExtra("group_id", section.groupId)) }
        }
        header.findViewById<TextView>(R.id.btn_manage_section).apply {
            visibility = if (group != null) View.VISIBLE else View.GONE
            contentDescription = "Manage group ${section.title}"
            setOnClickListener { showGroupSectionActions(section) }
        }
        return header
    }

    private fun showGroupSectionActions(section: PhormiTabOverview.Section) {
        val groupId = section.groupId ?: return
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle(section.title).setView(content).setNegativeButton("Cancel", null).create()
        fun action(label: String, click: () -> Unit) {
            content.addView(android.widget.Button(this).apply {
                text = label; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { dialog.dismiss(); click() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        action("Open group") { returnAction(Intent().putExtra("action", "open_group").putExtra("group_id", groupId)) }
        action("Close group tabs") {
            AlertDialog.Builder(this)
                .setTitle("Close ${section.tabs.size} ${if (section.tabs.size == 1) "tab" else "tabs"} in ${section.title}?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Close group") { _, _ ->
                    returnAction(Intent().putExtra("action", "close_group").putExtra("group_id", groupId))
                }.show()
        }
        action("Add / remove tabs") {
            startActivity(Intent(this, TabGroupsActivity::class.java).putExtra("group_id", groupId))
        }
        action("Rename, color or reorder") {
            startActivity(Intent(this, TabGroupsActivity::class.java).putExtra("group_id", groupId).putExtra("edit_group", true))
        }
        dialog.show()
    }

    private fun makeTab(layoutMode: String, item: PhormiTabOverview.TabEntry): View {
        val layout = when (layoutMode) {
            "grid" -> R.layout.item_overview_tab_grid
            "horizontal" -> R.layout.item_overview_tab
            else -> R.layout.item_overview_tab_list
        }
        val card = LayoutInflater.from(this).inflate(layout, tabsVertical, false)
        card.tag = "tab-card"
        card.setTag(R.id.overview_tab_card, PhormiTabOverview.layoutTag(layoutMode))
        val selected = item.id in selectedIds
        val active = item.id == activeId
        val title = PhormiTabOverview.displayTitle(item.title, item.url)
        val radius = when (layoutMode) { "grid" -> 14; "horizontal" -> 18; else -> 12 }
        card.background = GradientDrawable().apply {
            setColor(if (selected) PhormiUiColors.accentSoft(this@TabsOverviewActivity) else PhormiUiColors.surface(this@TabsOverviewActivity))
            setStroke(dp(if (active || selected) 2 else 1), if (active || selected) PhormiUiColors.accent(this@TabsOverviewActivity) else PhormiUiColors.border(this@TabsOverviewActivity))
            cornerRadius = dp(radius).toFloat()
        }
        card.findViewById<View>(R.id.tab_card_group_band)?.setBackgroundColor(
            if (item.groupId.isBlank()) PhormiUiColors.border(this) else PhormiUiColors.groupColor(this, item.groupColor)
        )
        card.findViewById<TextView>(R.id.tab_card_monogram).text = PhormiTabOverview.monogram(item.url)
        card.findViewById<TextView>(R.id.tab_card_title).text = title
        card.findViewById<TextView>(R.id.tab_card_address).text =
            if (layoutMode == "horizontal") PhormiTabOverview.address(item.url) else PhormiTabOverview.shortAddress(item.url)
        val status = buildList {
            if (active) add("Current")
            if (item.ghost) add("Ghost")
            if (item.profile != PhormiEnvironmentManager.DEFAULT_ENVIRONMENT) add(item.profile)
            if (isEmpty()) add(if (PhormiTabOverview.isNewTab(item.url)) "Not started" else "Open website")
        }.joinToString(" · ")
        card.findViewById<TextView>(R.id.tab_card_status).apply {
            text = status
            visibility = if (layoutMode == "vertical") View.GONE else View.VISIBLE
        }
        card.findViewById<TextView>(R.id.tab_card_check).apply {
            visibility = if (selectionMode) View.VISIBLE else View.GONE
            text = if (selected) "✓" else "○"
        }
        val open = card.findViewById<View>(R.id.tab_card_open)
        open.contentDescription = if (selectionMode) "${if (selected) "Deselect" else "Select"} $title" else "Open $title"
        open.setOnClickListener {
            if (selectionMode) {
                if (!selectedIds.add(item.id)) selectedIds.remove(item.id)
                updateSelectionUi(); applyFilter()
            } else returnAction(Intent().putExtra("action", "select").putExtra("tab_id", item.id))
        }
        open.setOnLongClickListener { showTabActions(item); true }
        card.findViewById<TextView>(R.id.btn_group_tab_card).apply {
            text = if (layoutMode == "vertical") "⊞" else if (item.groupId.isBlank()) "Group" else "Move"
            contentDescription = "Group $title"
            setOnClickListener { showGroupChooser(intArrayOf(item.id), item.groupId) }
        }
        card.findViewById<TextView>(R.id.btn_close_tab_card).apply {
            contentDescription = "Close $title"
            setOnClickListener { returnAction(Intent().putExtra("action", "close").putExtra("tab_id", item.id)) }
        }
        card.findViewById<TextView>(R.id.btn_more_tab_card).apply {
            contentDescription = "More actions for $title"
            setOnClickListener { showTabActions(item) }
        }
        return card
    }

    private fun showTabActions(item: PhormiTabOverview.TabEntry) {
        val title = PhormiTabOverview.displayTitle(item.title, item.url)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(content).setNegativeButton("Cancel", null).create()
        fun action(label: String, click: () -> Unit) {
            content.addView(android.widget.Button(this).apply {
                text = label; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { dialog.dismiss(); click() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        action(if (item.groupId.isBlank()) "Assign to a group" else "Move to another group") { showGroupChooser(intArrayOf(item.id), item.groupId) }
        action("Change browsing environment") { showEnvironmentChooser(item) }
        action("Select multiple tabs") {
            selectionMode = true; selectedIds.add(item.id); updateSelectionUi(); applyFilter()
        }
        action("Close tab") { returnAction(Intent().putExtra("action", "close").putExtra("tab_id", item.id)) }
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadTabs() {
        allTabs.clear()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        runCatching {
            val urls = org.json.JSONArray(prefs.getString("tab_urls", "[]") ?: "[]")
            val titles = org.json.JSONArray(prefs.getString("tab_titles", "[]") ?: "[]")
            val profiles = org.json.JSONArray(prefs.getString("tab_profiles", "[]") ?: "[]")
            val ids = org.json.JSONArray(prefs.getString("tab_ids", "[]") ?: "[]")
            activeId = ids.optInt(prefs.getInt("active_index", 0).coerceAtLeast(0), -1)
            val knownIds = (0 until ids.length()).map { ids.optInt(it, it + 1) }.filter { it > 0 }
            val manager = TabGroupManager(this)
            if (knownIds.isNotEmpty()) manager.prune(knownIds)
            val managedGroups = manager.list()
            groups = managedGroups.map { PhormiTabOverview.GroupInfo(it.id, it.name, it.color, it.collapsed) }
            if (groupFilter != null && groups.none { it.id == groupFilter }) groupFilter = null
            val groupByTab = managedGroups.flatMap { group -> group.tabIds.map { it to group } }.toMap()
            for (i in 0 until minOf(urls.length(), ids.length())) {
                val id = ids.optInt(i, i + 1)
                val group = groupByTab[id]
                val profile = profiles.optString(i, PhormiEnvironmentManager.DEFAULT_ENVIRONMENT).ifBlank { PhormiEnvironmentManager.DEFAULT_ENVIRONMENT }
                allTabs.add(PhormiTabOverview.TabEntry(
                    id = id,
                    title = titles.optString(i, "New tab"),
                    url = urls.optString(i, PhormiTabOverview.NEW_TAB_URL),
                    profile = profile,
                    groupId = group?.id.orEmpty(), groupName = group?.name.orEmpty(), groupColor = group?.color ?: 0,
                    ghost = profile.equals("Ghost", ignoreCase = true)
                ))
            }
        }
    }

    private fun showEnvironmentChooser(item: PhormiTabOverview.TabEntry) {
        val envs = (listOf(PhormiEnvironmentManager.DEFAULT_ENVIRONMENT) +
            PhormiEnvironmentManager.list().filter { it != "Ghost" && it != PhormiEnvironmentManager.DEFAULT_ENVIRONMENT }).distinct()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle("Browsing environment").setView(content).setNegativeButton("Cancel", null).create()
        envs.forEach { env ->
            content.addView(android.widget.Button(this).apply {
                text = if (env == item.profile) "✓  $env" else env; isAllCaps = false
                setOnClickListener {
                    dialog.dismiss()
                    returnAction(Intent().putExtra("action", "reassign_env").putExtra("tab_id", item.id).putExtra("profile", env))
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        dialog.show()
    }

    private fun showGroupChooser(tabIds: IntArray, currentGroupId: String? = null) {
        if (tabIds.isEmpty()) return
        val managed = TabGroupManager(this).list()
        val single = if (tabIds.size == 1) allTabs.firstOrNull { it.id == tabIds[0] } else null
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (single != null) "Organize this tab" else "Group ${tabIds.size} tabs")
            .setView(content).setNegativeButton("Cancel", null).create()
        fun row(label: String, click: () -> Unit) {
            content.addView(android.widget.Button(this).apply {
                text = label; isAllCaps = false
                contentDescription = "Group choice $label"
                setOnClickListener { dialog.dismiss(); click() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        fun assign(groupId: String) {
            val intent = Intent()
            if (single != null) {
                intent.putExtra("action", "assign_group").putExtra("group_id", groupId)
                    .putExtra("tab_id", single.id).putExtra("url", single.url)
            } else {
                intent.putExtra("action", "assign_group_many").putExtra("group_id", groupId).putExtra("tab_ids", tabIds)
            }
            returnAction(intent)
        }
        managed.forEach { group ->
            val checked = currentGroupId == group.id || (single != null && single.groupId == group.id)
            row(if (checked) "✓  ${group.name}" else group.name) { assign(group.id) }
        }
        val anyGrouped = currentGroupId?.isNotBlank() == true || tabIds.any { id -> allTabs.firstOrNull { it.id == id }?.groupId?.isNotBlank() == true }
        if (anyGrouped) row("Remove from group") {
            val intent = if (single != null) Intent().putExtra("action", "unassign_group").putExtra("tab_id", single.id)
            else Intent().putExtra("action", "unassign_group_many").putExtra("tab_ids", tabIds)
            returnAction(intent)
        }
        row("+  Create a group") {
            val input = EditText(this).apply { hint = "Group name"; setSingleLine(); setPadding(dp(4), dp(12), dp(4), dp(12)) }
            val box = LinearLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
            AlertDialog.Builder(this).setTitle("New group").setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Create and add") { _, _ ->
                    val group = TabGroupManager(this).create(input.text.toString())
                    assign(group.id)
                }.show()
        }
        dialog.show()
    }
}
