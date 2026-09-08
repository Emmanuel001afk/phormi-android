from pathlib import Path
R=Path(__file__).resolve().parents[1]
p=R/'app/src/main/java/com/uong/phormi/PhormiQuickAccessRenderer.kt'
s=p.read_text()
s=s.replace('''        val custom = readCustom(context).filterNot { PhormiFavorites.contains(context, it.url) }
        val pinned = (fixed + custom).distinctBy { it.url }.take(10)
        val mostVisited = PhormiVisitTracker.top(context, 10).map { Item(it.title, it.url, "Most visited", it.visits) }
        val combined = (pinned + mostVisited).distinctBy { it.url }.take(10)
''','''        val add = Item("+ Add", "", "Add")
        val custom = readCustom(context)
        val favorites = PhormiFavorites.getAll(context).filter { PhormiFavorites.contains(context, it.url) }.map { Item(it.title, it.url, "Favorite") }
        val frequent = PhormiVisitTracker.top(context, 20).filter { it.visits > 10 }.take(10).map { Item(it.title, it.url, "Frequent", it.visits) }
        val combined = (fixed + add + custom + favorites + frequent).distinctBy { if (it.kind == "Add") "__add__" else it.url }.take(20)
''')
s=s.replace('''if (item.kind == "Most visited") append(" · ${item.visits} visits")''','''if (item.kind == "Frequent") append(" · ${item.visits} visits")''')
s=s.replace('''setOnClickListener { onOpen(item.url, item.kind == "Pinned") }''','''setOnClickListener {
                        if (item.kind == "Add") showAddShortcutDialog(context, rows, onOpen) else onOpen(item.url, item.kind == "Pinned")
                    }''')
s=s.replace('''if (item.kind == "Most visited") {''','''if (item.kind == "Frequent") {''')
needle='''    private fun readCustom(context: Context): List<Item> {'''
helper='''    private fun showAddShortcutDialog(context: Context, rows: LinearLayout, onOpen: (String, Boolean) -> Unit) {
        val activity = context as? android.app.Activity ?: return
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val name = android.widget.EditText(activity).apply { hint = "Name"; isSingleLine = true }
        val url = android.widget.EditText(activity).apply { hint = "https://example.com"; isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        box.addView(name); box.addView(url)
        android.app.AlertDialog.Builder(activity).setTitle("Add Quick Access").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ ->
            val n=name.text.toString().trim().ifBlank { url.text.toString().trim() }; val u=url.text.toString().trim()
            if (n.isNotBlank() && u.startsWith("http")) {
                val prefs=activity.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
                val arr=runCatching { JSONArray(prefs.getString("custom_shortcuts", "[]") ?: "[]") }.getOrElse { JSONArray() }
                arr.put(org.json.JSONObject().put("name",n).put("url",u)); prefs.edit().putString("custom_shortcuts",arr.toString()).apply(); render(activity,rows,onOpen)
            }
        }.show()
    }

'''
s=s.replace(needle,helper+needle)
p.write_text(s)
# Fix API/lint blockers that were exposed before Kotlin compilation.
for f in ['app/src/main/java/com/uong/phormi/MainActivity.kt','app/src/main/java/com/uong/phormi/PhormiKeyboardService.kt','app/src/main/java/com/uong/phormi/PhormiKeyboardSettingsActivity.kt','app/src/main/java/com/uong/phormi/GhostActivity.kt']:
    q=R/f; t=q.read_text()
    if f.endswith('MainActivity.kt'):
        t=t.replace('else callback?.showInterstitial(true)','else if (android.os.Build.VERSION.SDK_INT >= 27) callback?.showInterstitial(true)')
        t=t.replace('contentResolver.takePersistableUriPermission(uri, data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))','contentResolver.takePersistableUriPermission(uri, data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))')
    if f.endswith('PhormiKeyboardService.kt'):
        t=t.replace('runCatching { switchToNextInputMethod(false) }','runCatching { if (android.os.Build.VERSION.SDK_INT >= 28) switchToNextInputMethod(false) else (getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()) }')
    if f.endswith('PhormiKeyboardSettingsActivity.kt'):
        t=t.replace('val selected = imm.currentInputMethodInfo?.id == serviceId','val selected = if (android.os.Build.VERSION.SDK_INT >= 34) imm.currentInputMethodInfo?.id == serviceId else false')
    if f.endswith('GhostActivity.kt'):
        t=t.replace('override fun onBackPressed() { if (active()?.canGoBack() == true) active()?.goBack() else finishAndClear() }','override fun onBackPressed() { if (active()?.canGoBack() == true) active()?.goBack() else { finishAndClear(); super.onBackPressed() } }')
    q.write_text(t)
print('Round 10 compile fixes applied')
