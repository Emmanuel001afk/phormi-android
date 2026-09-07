from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
main = root / "app/src/main/java/com/uong/phormi/MainActivity.kt"
s = main.read_text()

# Browser Lock follows the whole-app lifecycle, not MainActivity's individual screen lifecycle.
# Opening Menu, Downloads, Accounts, Favorites, Keyboard settings, etc. therefore never relocks Phormi.
for imp in [
    "import androidx.lifecycle.DefaultLifecycleObserver\n",
    "import androidx.lifecycle.LifecycleOwner\n",
    "import androidx.lifecycle.ProcessLifecycleOwner\n",
]:
    if imp not in s:
        s = s.replace("import androidx.lifecycle.lifecycleScope\n", "import androidx.lifecycle.lifecycleScope\n" + imp, 1)
if "private val browserAppLifecycleObserver" not in s:
    marker = "    private var browserLockOverlay: View? = null\n"
    observer = '''    private val browserAppLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (::prefs.isInitialized && browserLockManager.isEnabled(prefs)) {
                browserUnlockedThisSession = false
            }
        }
    }
'''
    s = s.replace(marker, marker + observer, 1)
if "ProcessLifecycleOwner.get().lifecycle.addObserver(browserAppLifecycleObserver)" not in s:
    s = s.replace("        browserLockManager = BrowserLockManager(this, mainExecutor)\n", "        browserLockManager = BrowserLockManager(this, mainExecutor)\n        ProcessLifecycleOwner.get().lifecycle.addObserver(browserAppLifecycleObserver)\n", 1)
s = s.replace("        unifiedSearchExecutor.shutdownNow()\n", "        unifiedSearchExecutor.shutdownNow()\n        ProcessLifecycleOwner.get().lifecycle.removeObserver(browserAppLifecycleObserver)\n", 1)
# Replace the old MainActivity.onStop relock behavior with persistence only.
s = re.sub(
    r'''    override fun onStop\(\) \{\n        if \(::prefs\.isInitialized\) \{\n            saveTabsImmediate\(true\).*?\n        \}\n        super\.onStop\(\)\n    \}''',
    '''    override fun onStop() {
        if (::prefs.isInitialized) saveTabsImmediate(true)
        super.onStop()
    }''',
    s,
    count=1,
    flags=re.S,
)

# Favorites are visually filled with Phormi's accent when active, while retaining the normal icon shape.
s = re.sub(
    r'''        button\.alpha = if \(favorite\) 1f else 0\.55f\n        button\.contentDescription''',
    '''        button.alpha = 1f
        val accent = if (prefs.getBoolean(KEY_DAILY_ACCENT, true)) dailyAccent() else Color.rgb(56, 189, 248)
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(if (favorite) accent else Color.TRANSPARENT)
        button.contentDescription''',
    s,
    count=1,
)

# Replace the old mixed Quick Access renderer with a dedicated renderer that keeps
# Favorites separate and promotes sites after 11 recorded visits.
s = s.replace("private fun loadQuickAccessRows()", "private fun legacyLoadQuickAccessRows()", 1)
s = s.replace("loadQuickAccessRows()", "renderQuickAccessRows()")
if "private fun renderQuickAccessRows()" not in s:
    marker = "    private fun legacyLoadQuickAccessRows()"
    helper = '''    private fun renderQuickAccessRows() {
        val rows = findViewById<LinearLayout>(R.id.quick_access_rows) ?: return
        PhormiQuickAccessRenderer.render(this, rows) { target, newTab ->
            if (newTab) createNewTab(target) else openShortcut(target)
        }
    }

'''
    s = s.replace(marker, helper + marker, 1)

# Count normal page visits for Most Visited suggestions. Ghost/private tabs are never recorded.
needle = '''                    if (tab != null && !tab.isGhost) {
                        HistoryActivity.record(this@MainActivity, t, u)
                    }
'''
replacement = '''                    if (tab != null && !tab.isGhost) {
                        HistoryActivity.record(this@MainActivity, t, u)
                        PhormiVisitTracker.record(this@MainActivity, t, u)
                    }
'''
if needle in s and "PhormiVisitTracker.record(this@MainActivity, t, u)" not in s:
    s = s.replace(needle, replacement, 1)

# Harden normal WebViews: safe browsing on, and keep the existing compatibility/security preference layer.
if "safeBrowsingEnabled = true" not in s:
    s = s.replace("            domStorageEnabled = true\n", "            domStorageEnabled = true\n            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true\n", 1)

# Make Phormi Search visually closer to a conventional search-results page: compact header,
# clear title/domain/snippet hierarchy, no diagnostic wall of text, and a restrained source chip.
search_pattern = re.compile(r'''    private fun unifiedSearchHtml\(\n.*?\n    \}\n\n    private fun ''', re.S)
match = search_pattern.search(s)
if match:
    replacement = '''    private fun unifiedSearchHtml(
        query: String,
        results: List<UnifiedResult>,
        loading: Boolean,
        completed: Int,
        total: Int,
        successfulEngines: List<String> = emptyList(),
        finishedEngines: List<String> = emptyList(),
        aiAnswer: String? = null
    ): String {
        val q = Html.escapeHtml(query)
        val dark = prefs.getString(KEY_THEME_MODE, "System") == "Dark"
        val bg = if (dark) "#0b1220" else "#ffffff"
        val fg = if (dark) "#e5e7eb" else "#172033"
        val muted = if (dark) "#94a3b8" else "#64748b"
        val line = if (dark) "#263244" else "#e5e7eb"
        val link = if (dark) "#38bdf8" else "#1769aa"
        val card = if (dark) "#111827" else "#ffffff"
        val body = if (results.isEmpty() && loading) {
            "<div class='status'>Searching…</div>"
        } else if (results.isEmpty()) {
            "<div class='status'>No results found. Try a different search.</div>"
        } else {
            results.joinToString("\\n") { result ->
                val title = Html.escapeHtml(result.title)
                val url = Html.escapeHtml(result.url)
                val host = runCatching { URL(result.url).host.removePrefix("www.") }.getOrDefault("")
                val snippet = Html.escapeHtml(result.snippet.ifBlank { "Open this result to view the page." })
                val source = Html.escapeHtml(result.source.split(" · ").take(3).joinToString(" · "))
                """
                <article class='result'>
                  <div class='domain'>$host</div>
                  <a class='title' href='$url'>$title</a>
                  <div class='url'>$url</div>
                  <div class='snippet'>$snippet</div>
                  <div class='source'>$source</div>
                </article>
                """.trimIndent()
            }
        }
        val aiSection = aiAnswer?.takeIf { it.isNotBlank() }?.let { answer ->
            "<section class='ai'><div class='ai-title'>Phormi AI</div><div>${Html.escapeHtml(answer)}</div></section>"
        }.orEmpty()
        return """
            <!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>
            body{margin:0;background:$bg;color:$fg;font-family:Arial,Roboto,sans-serif;-webkit-text-size-adjust:100%}
            .top{padding:16px 18px 12px;position:sticky;top:0;background:$bg;border-bottom:1px solid $line;z-index:2}
            .brand{font-size:20px;font-weight:700;letter-spacing:-.2px}.query{margin-top:5px;color:$muted;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            .coverage{padding:8px 18px;color:$muted;font-size:11px;border-bottom:1px solid $line}
            .result{padding:15px 18px;border-bottom:1px solid $line;background:$card}.domain{font-size:11px;color:$muted;margin-bottom:3px}.title{display:block;font-size:17px;line-height:1.3;color:$link;text-decoration:none;font-weight:600}.url{font-size:10px;color:$muted;margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.snippet{font-size:13px;line-height:1.5;margin-top:7px;color:$fg}.source{display:inline-block;margin-top:9px;padding:3px 7px;border:1px solid $line;border-radius:10px;color:$muted;font-size:9px}
            .status{padding:30px 18px;color:$muted}.ai{margin:12px 14px;padding:13px;border:1px solid $line;border-radius:14px;background:$card}.ai-title{color:$link;font-weight:700}.ai div{margin-top:7px;line-height:1.5;font-size:13px}.footer{padding:18px;color:$muted;font-size:11px;line-height:1.5}
            </style></head><body>
            <div class='top'><div class='brand'>Phormi Search</div><div class='query'>$q</div></div>
            <div class='coverage'>${if (loading) "Searching $completed/$total sources…" else "${successfulEngines.size.coerceAtLeast(finishedEngines.size)}/$total sources responded"}</div>
            $aiSection$body
            <div class='footer'>Results are combined from the available search providers. Source labels show where matching results were found.</div>
            </body></html>
        """.trimIndent()
    }

    private fun '''
    s = s[:match.start()] + replacement + s[match.end():]

main.write_text(s)
print("Applied Round 6 browser lifecycle, Quick Access, visit tracking, favorite state, WebView safety, and search presentation repairs")
