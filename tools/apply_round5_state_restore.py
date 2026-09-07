from pathlib import Path

path = Path("app/src/main/java/com/uong/phormi/MainActivity.kt")
text = path.read_text(encoding="utf-8")
marker = "// PHORMI_ROUND5_STATE_RESTORE_V1"
if marker in text:
    print("Round 5 state restoration already applied")
    raise SystemExit(0)

text = text.replace(
    'private const val KEY_TAB_ENVIRONMENT = "tab_environment"',
    'private const val KEY_TAB_ENVIRONMENT = "tab_environment"\n        private const val KEY_TAB_WEBVIEW_STATES = "tab_webview_states"\n        private const val WEBVIEW_STATE_MAX_BYTES = 64 * 1024',
    1,
)

old_sig = 'private fun createNewTab(url: String, requestedProfile: String? = null, forceGhost: Boolean = false, requestedId: Int? = null, requestedCreatedAt: Long? = null) {'
new_sig = 'private fun createNewTab(url: String, requestedProfile: String? = null, forceGhost: Boolean = false, requestedId: Int? = null, requestedCreatedAt: Long? = null, restoredStateBase64: String? = null) {'
assert old_sig in text
text = text.replace(old_sig, new_sig, 1)

old_load = '        if (url != NEW_TAB_URL) webView.loadUrl(url)\n        switchToTab(id)'
new_load = '''        var restored = false
        if (!restoredStateBase64.isNullOrBlank() && WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) {
            restored = runCatching {
                restoreWebViewState(webView, restoredStateBase64)
            }.getOrDefault(false)
        }
        if (!restored && url != NEW_TAB_URL) webView.loadUrl(url)
        switchToTab(id)'''
assert old_load in text
text = text.replace(old_load, new_load, 1)

old_save_sig = 'private fun saveTabs() {\n        tabSaveRunnable?.let { tabSaveHandler.removeCallbacks(it) }\n        val runnable = Runnable { saveTabsImmediate() }'
new_save_sig = 'private fun saveTabs() {\n        tabSaveRunnable?.let { tabSaveHandler.removeCallbacks(it) }\n        val runnable = Runnable { saveTabsImmediate(false) }'
assert old_save_sig in text
text = text.replace(old_save_sig, new_save_sig, 1)

old_immediate = '''    private fun saveTabsImmediate() {
        tabSaveRunnable?.let { tabSaveHandler.removeCallbacks(it) }
        tabSaveRunnable = null
        val persistTabs = tabs.filterNot { it.isGhost }
        val urls = JSONArray()
        val titles = JSONArray()
        val lastUsed = JSONArray()
        val createdAt = JSONArray()
        val profiles = JSONArray()
        val ids = JSONArray()
        persistTabs.forEach { tab ->
            val u = tab.webView.url?.takeIf { it.isNotBlank() } ?: NEW_TAB_URL
            urls.put(u)
            titles.put(tab.title.ifBlank { "Tab" })
            lastUsed.put(tab.lastUsed)
            createdAt.put(tab.createdAt)
            profiles.put(tab.profileName)
            ids.put(tab.id)
        }'''
new_immediate = '''    private fun saveTabsImmediate(captureWebViewState: Boolean = false) {
        tabSaveRunnable?.let { tabSaveHandler.removeCallbacks(it) }
        tabSaveRunnable = null
        val persistTabs = tabs.filterNot { it.isGhost }
        val urls = JSONArray()
        val titles = JSONArray()
        val lastUsed = JSONArray()
        val createdAt = JSONArray()
        val profiles = JSONArray()
        val ids = JSONArray()
        val webViewStates = JSONArray()
        persistTabs.forEach { tab ->
            val u = tab.webView.url?.takeIf { it.isNotBlank() } ?: NEW_TAB_URL
            urls.put(u)
            titles.put(tab.title.ifBlank { "Tab" })
            lastUsed.put(tab.lastUsed)
            createdAt.put(tab.createdAt)
            profiles.put(tab.profileName)
            ids.put(tab.id)
            webViewStates.put(if (captureWebViewState) (saveWebViewState(tab.webView) ?: "") else "")
        }'''
assert old_immediate in text
text = text.replace(old_immediate, new_immediate, 1)

old_empty = '            urls.put(NEW_TAB_URL); titles.put(getString(R.string.new_tab)); lastUsed.put(System.currentTimeMillis()); createdAt.put(System.currentTimeMillis()); profiles.put(DEFAULT_PROFILE_NAME); ids.put(nextTabId++)'
new_empty = '            urls.put(NEW_TAB_URL); titles.put(getString(R.string.new_tab)); lastUsed.put(System.currentTimeMillis()); createdAt.put(System.currentTimeMillis()); profiles.put(DEFAULT_PROFILE_NAME); ids.put(nextTabId++); webViewStates.put("")'
assert old_empty in text
text = text.replace(old_empty, new_empty, 1)

old_prefs = '''            .putString(KEY_TAB_PROFILES, profiles.toString())
            .putString(KEY_TAB_IDS, ids.toString())
            .putString(KEY_TAB_RETENTION, prefs.getString(KEY_TAB_RETENTION, RETENTION_NEVER) ?: RETENTION_NEVER)'''
new_prefs = '''            .putString(KEY_TAB_PROFILES, profiles.toString())
            .putString(KEY_TAB_IDS, ids.toString())
            .putString(KEY_TAB_WEBVIEW_STATES, webViewStates.toString())
            .putString(KEY_TAB_RETENTION, prefs.getString(KEY_TAB_RETENTION, RETENTION_NEVER) ?: RETENTION_NEVER)'''
assert old_prefs in text
text = text.replace(old_prefs, new_prefs, 1)

old_restore_arrays = '''            val createdAt = runCatching { JSONArray(prefs.getString(KEY_TAB_CREATED_AT, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val ids = runCatching { JSONArray(prefs.getString(KEY_TAB_IDS, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val count = arr.length()'''
new_restore_arrays = '''            val createdAt = runCatching { JSONArray(prefs.getString(KEY_TAB_CREATED_AT, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val ids = runCatching { JSONArray(prefs.getString(KEY_TAB_IDS, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val webViewStates = runCatching { JSONArray(prefs.getString(KEY_TAB_WEBVIEW_STATES, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val count = arr.length()'''
assert old_restore_arrays in text
text = text.replace(old_restore_arrays, new_restore_arrays, 1)

old_create = '                createNewTab(restoredUrl, restoredProfile, requestedId = restoredId, requestedCreatedAt = restoredCreatedAt)'
new_create = '                val restoredState = webViewStates.optString(i, "").trim().takeIf { it.isNotBlank() }\n                createNewTab(restoredUrl, restoredProfile, requestedId = restoredId, requestedCreatedAt = restoredCreatedAt, restoredStateBase64 = restoredState)'
assert old_create in text
text = text.replace(old_create, new_create, 1)

insert_before = '    private fun retentionAgeMillis(): Long? = when (prefs.getString(KEY_TAB_RETENTION, RETENTION_NEVER)) {'
helpers = '''    // PHORMI_ROUND5_STATE_RESTORE_V1
    // WebViewCompat.saveState() preserves the navigation stack and page state while
    // enforcing a hard size limit. The serialized Bundle is stored per tab so the
    // browser can rebuild WebViews after process death instead of only reloading URLs.
    private fun saveWebViewState(webView: WebView): String? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) return null
        return runCatching {
            val state = Bundle()
            WebViewCompat.saveState(webView, state, WEBVIEW_STATE_MAX_BYTES, false)
            val parcel = android.os.Parcel.obtain()
            try {
                state.writeToParcel(parcel, 0)
                android.util.Base64.encodeToString(parcel.marshall(), android.util.Base64.NO_WRAP)
            } finally {
                parcel.recycle()
            }
        }.getOrNull()
    }

    private fun restoreWebViewState(webView: WebView, encoded: String): Boolean {
        if (encoded.isBlank()) return false
        return runCatching {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val parcel = android.os.Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val state = Bundle.CREATOR.createFromParcel(parcel)
                state.classLoader = MainActivity::class.java.classLoader
                val history = webView.restoreState(state)
                history != null && history.size > 0
            } finally {
                parcel.recycle()
            }
        }.getOrDefault(false)
    }

'''
assert insert_before in text
text = text.replace(insert_before, helpers + insert_before, 1)

old_pause = '        saveTabsImmediate()\n        CookieManager.getInstance().flush()\n        super.onPause()'
new_pause = '        saveTabsImmediate(true)\n        CookieManager.getInstance().flush()\n        super.onPause()'
assert old_pause in text
text = text.replace(old_pause, new_pause, 1)

old_stop = '        if (::prefs.isInitialized) {\n            saveTabsImmediate()'
new_stop = '        if (::prefs.isInitialized) {\n            saveTabsImmediate(true)'
assert old_stop in text
text = text.replace(old_stop, new_stop, 1)

# Also capture state before Android may destroy the Activity/process.
anchor = '    override fun onUserLeaveHint() {'
method = '''    override fun onSaveInstanceState(outState: Bundle) {
        saveTabsImmediate(true)
        super.onSaveInstanceState(outState)
    }

'''
assert anchor in text
text = text.replace(anchor, method + anchor, 1)

path.write_text(text, encoding="utf-8")
print("Applied Round 5 WebView state restoration")
