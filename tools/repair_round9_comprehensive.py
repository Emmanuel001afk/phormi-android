from pathlib import Path
R=Path(__file__).resolve().parents[1]

def patch(rel, old, new, once=True):
    p=R/rel; t=p.read_text()
    if old not in t:
        print('SKIP/MISS', rel, old[:70].replace('\n',' ')); return
    p.write_text(t.replace(old,new,1) if once else t.replace(old,new)); print('PATCH',rel)

# Imports needed by the new Round 9 UI helpers.
patch('app/src/main/java/com/uong/phormi/MainActivity.kt', 'import android.view.LayoutInflater\n', 'import android.view.LayoutInflater\nimport android.view.Gravity\n')
patch('app/src/main/java/com/uong/phormi/PhormiKeyboardSettingsActivity.kt', 'import android.widget.CheckBox\n', 'import android.widget.CheckBox\nimport android.widget.EditText\n')
patch('app/src/main/java/com/uong/phormi/TabGroupsActivity.kt', 'import android.widget.TextView\n', 'import android.widget.TextView\nimport android.widget.Toast\n')

# Favorite indicator: color only the center, not the entire decagon.
patch('app/src/main/java/com/uong/phormi/MainActivity.kt', '''        button.alpha = 1f
        val accent = if (prefs.getBoolean(KEY_DAILY_ACCENT, true)) dailyAccent() else Color.rgb(56, 189, 248)
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(if (favorite) accent else Color.TRANSPARENT)
        button.contentDescription = if (favorite) "Remove current page from favorites" else "Add current page to favorites"''', '''        button.alpha = 1f
        val accent = if (prefs.getBoolean(KEY_DAILY_ACCENT, true)) dailyAccent() else Color.rgb(56, 189, 248)
        // The decagon remains an outline; only its center indicator changes color.
        button.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (favorite) accent else Color.TRANSPARENT)
        }
        button.contentDescription = if (favorite) "Remove current page from favorites" else "Add current page to favorites"''')

# Google OAuth: never fake an OAuth-capable browser by UA spoofing; use secure Custom Tabs.
patch('app/src/main/java/com/uong/phormi/MainActivity.kt', '''                val targetUri = request?.url ?: return false
                val target = targetUri.toString()
                if (target.isBlank()) return false''', '''                val targetUri = request?.url ?: return false
                val target = targetUri.toString()
                if (target.isBlank()) return false
                if (isGoogleAuthUrl(target)) {
                    openSecureAuthSurface(target)
                    return true
                }''')
patch('app/src/main/java/com/uong/phormi/MainActivity.kt', '''    private fun shareCurrentPage() {''', '''    private fun isGoogleAuthUrl(url: String): Boolean {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase(Locale.US).orEmpty()
        return host == "accounts.google.com" || host.endsWith(".accounts.google.com") ||
            (host == "www.google.com" && (u.path.orEmpty().contains("/signin") || u.path.orEmpty().contains("/oauth")))
    }

    private fun openSecureAuthSurface(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Secure Google sign-in")
            .setMessage("Google blocks OAuth sign-in inside Android WebView. Phormi will open Google's secure browser sign-in surface instead.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open secure sign-in") { _, _ ->
                runCatching {
                    androidx.browser.customtabs.CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .setUrlBarHidingEnabled(false)
                        .build()
                        .launchUrl(this, Uri.parse(url))
                }.onFailure { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            }.show()
    }

    private fun shareCurrentPage() {''')

# Find-in-page: live match count plus next/previous navigation.
patch('app/src/main/java/com/uong/phormi/MainActivity.kt', '''    private fun showFindInPage() {
        val view = activeWebView() ?: return
        val input = EditText(this).apply { hint = "Find text"; setSingleLine(true) }
        AlertDialog.Builder(this)
            .setTitle("Find in page")
            .setView(input)
            .setNegativeButton("Close") { _, _ -> view.clearMatches() }
            .setPositiveButton("Find") { _, _ ->
                val q = input.text.toString()
                if (q.isNotBlank()) view.findAllAsync(q)
            }.show()
    }''', '''    private fun showFindInPage() {
        val view = activeWebView() ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 0, 8, 0) }
        val input = EditText(this).apply { hint = "Find text"; setSingleLine(true) }
        val count = TextView(this).apply { text = "0 matches"; setPadding(0, 8, 0, 4) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val previous = Button(this).apply { text = "Previous" }
        val next = Button(this).apply { text = "Next" }
        controls.addView(previous, LinearLayout.LayoutParams(0, 48, 1f))
        controls.addView(next, LinearLayout.LayoutParams(0, 48, 1f))
        box.addView(input); box.addView(count); box.addView(controls)
        view.setFindListener { _, numberOfMatches, _ -> count.text = "$numberOfMatches matches" }
        val dialog = AlertDialog.Builder(this).setTitle("Find in page").setView(box)
            .setNegativeButton("Close") { _, _ -> view.clearMatches() }.create()
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count0: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count0: Int) { if (!s.isNullOrBlank()) view.findAllAsync(s.toString()) else { view.clearMatches(); count.text = "0 matches" } }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        previous.setOnClickListener { view.findNext(false) }
        next.setOnClickListener { view.findNext(true) }
        dialog.setOnDismissListener { view.clearMatches() }
        dialog.show()
    }''')

# Clearer menu wording.
patch('app/src/main/res/layout/activity_menu.xml', 'android:text="Save page as PDF"', 'android:text="Print / save page as PDF"')
patch('app/src/main/res/layout/activity_menu.xml', 'android:text="Page Capsule"', 'android:text="Save page position"')
patch('app/src/main/res/layout/activity_menu.xml', 'android:text="DM"', 'android:text="Desktop mode"')
patch('app/src/main/res/layout/activity_menu.xml', 'android:text="Same-page split"', 'android:text="Same-page split (this page)"')
patch('app/src/main/res/layout/activity_menu.xml', 'android:text="Notifications"', 'android:text="App notifications"')
patch('app/src/main/res/layout/activity_menu.xml', 'android:text="VPN"', 'android:text="VPN (requires VPN engine)"')

# Named environments: verify the profile actually attached to the new WebView.
patch('app/src/main/java/com/uong/phormi/PhormiEnvironmentManager.kt', '''    fun apply(webView: WebView, name: String): Boolean {
        val normalized = normalize(name)
        if (normalized == DEFAULT_ENVIRONMENT) return true
        if (!ensure(normalized)) return false
        return runCatching {
            WebViewCompat.setProfile(webView, normalized)
            true
        }.getOrDefault(false)
    }''', '''    fun apply(webView: WebView, name: String): Boolean {
        val normalized = normalize(name)
        if (!isSupported()) return normalized == DEFAULT_ENVIRONMENT
        return runCatching {
            if (normalized != DEFAULT_ENVIRONMENT) ensure(normalized)
            WebViewCompat.setProfile(webView, normalized)
            WebViewCompat.getProfile(webView)?.name == normalized
        }.getOrDefault(false)
    }''')

# Direct tab membership editor from the group screen.
patch('app/src/main/java/com/uong/phormi/TabGroupsActivity.kt', '''                setPadding(0, 4, 0, 8)
            })

            group.tabIds.forEach { tabId ->''', '''                setPadding(0, 4, 0, 8)
            })
            row.addView(TextView(this).apply {
                text = "Add / remove tabs"
                textSize = 12f
                setTextColor(0xFF38BDF8.toInt())
                setPadding(4, 4, 4, 8)
                setOnClickListener { chooseTabsForGroup(group.id) }
            })

            group.tabIds.forEach { tabId ->''')
patch('app/src/main/java/com/uong/phormi/TabGroupsActivity.kt', '''    private fun createGroup() {''', '''    private fun chooseTabsForGroup(groupId: String) {
        val tabs = readCurrentTabs()
        if (tabs.isEmpty()) { Toast.makeText(this, "There are no open tabs to assign.", Toast.LENGTH_SHORT).show(); return }
        val manager = TabGroupManager(this)
        val current = manager.groupForId(groupId)?.tabIds?.toSet().orEmpty()
        val labels = tabs.map { "${it.second} — ${it.third}" }.toTypedArray()
        val checked = BooleanArray(tabs.size) { current.contains(tabs[it].first) }
        AlertDialog.Builder(this).setTitle("Tabs in this group")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val id = tabs[which].first
                if (isChecked) manager.assignTab(groupId, id, tabs[which].third) else manager.unassignTab(id)
            }
            .setPositiveButton("Done") { _, _ -> render() }.show()
    }

    private fun createGroup() {''')

# Keyboard settings: concrete test path.
patch('app/src/main/java/com/uong/phormi/PhormiKeyboardSettingsActivity.kt', '''        root.addView(Button(this).apply {
            text = "Language & subtype settings"''', '''        root.addView(Button(this).apply {
            text = "Test Phormi Keyboard"
            setOnClickListener {
                val input = EditText(this@PhormiKeyboardSettingsActivity).apply { hint = "Type here to test Phormi Keyboard"; minLines = 2 }
                androidx.appcompat.app.AlertDialog.Builder(this@PhormiKeyboardSettingsActivity)
                    .setTitle("Keyboard test").setView(input)
                    .setPositiveButton("Choose keyboard") { _, _ ->
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
                        input.requestFocus()
                    }.setNegativeButton("Close", null).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Language & subtype settings"''')

patch('app/src/main/java/com/uong/phormi/MainActivity.kt', '"Page Capsule saved"', '"Page position saved"')
patch('app/src/main/java/com/uong/phormi/MainActivity.kt', '.setTitle("Page Capsule")', '.setTitle("Saved page position")')

print('Round 9 comprehensive audit patch applied')
