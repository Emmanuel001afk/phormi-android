package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION = "menu_action"
        const val ACTION_NEW_TAB = "new_tab"
        const val ACTION_GHOST = "ghost"
        const val ACTION_BROWSER_LOCK = "browser_lock"
        const val ACTION_THEME = "theme"
        const val ACTION_SETTINGS = "settings"
        const val ACTION_TAB_RETENTION = "tab_retention"
        const val ACTION_PULL_TO_REFRESH = "pull_to_refresh"
        const val ACTION_SPLIT_SCREEN = "split_screen"
        const val ACTION_KEEP_SCREEN_ON = "keep_screen_on"
        const val ACTION_TAB_ENVIRONMENT = "tab_environment"
        const val ACTION_NAVIGATION_LENS = "navigation_lens"
        const val ACTION_OBJECT_ANCHORS = "object_anchors"
        const val ACTION_SAME_PAGE_SPLIT = "same_page_split"
        const val ACTION_NOTIFICATIONS = "notifications"
        const val ACTION_SECURITY = "security"
        const val ACTION_SITE_LOCK = "site_lock"
        const val ACTION_FIND = "find"
        const val ACTION_SHARE = "share"
        const val ACTION_DESKTOP_MODE = "desktop_mode"
        const val ACTION_FAVORITE = "favorite"
        const val ACTION_HELP = "help"
        const val ACTION_BOOKMARKS = "bookmarks"
        const val ACTION_HISTORY = "history"
        const val ACTION_ACCOUNTS = "accounts"
        private const val REQ_NESTED = 2100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        wire(R.id.menu_new_tab) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_NEW_TAB))
            finish()
        }
        wire(R.id.menu_ghost) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_GHOST))
            finish()
        }
        wire(R.id.menu_tabs) {
            startActivity(Intent(this, TabsOverviewActivity::class.java))
            finish()
        }
        wire(R.id.menu_downloads) {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        wire(R.id.menu_bookmarks) {
            startActivityForResult(Intent(this, BookmarksActivity::class.java), REQ_NESTED)
        }
        wire(R.id.menu_history) {
            startActivityForResult(Intent(this, HistoryActivity::class.java), REQ_NESTED)
        }
        findViewById<TextView>(R.id.menu_notifications)?.let { wire(R.id.menu_notifications) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_NOTIFICATIONS)); finish() } }
        findViewById<TextView>(R.id.menu_security)?.let { wire(R.id.menu_security) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_SECURITY)); finish() } }
        findViewById<TextView>(R.id.menu_site_lock)?.let { wire(R.id.menu_site_lock) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_SITE_LOCK)); finish() } }
        findViewById<TextView>(R.id.menu_find)?.let { wire(R.id.menu_find) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_FIND)); finish() } }
        findViewById<TextView>(R.id.menu_share)?.let { wire(R.id.menu_share) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_SHARE)); finish() } }
        wire(R.id.menu_vpn) {
            startActivity(Intent(this, VpnActivity::class.java))
        }
        wire(R.id.menu_ai) {
            startActivity(Intent(this, AiActivity::class.java))
        }
        wire(R.id.menu_browser_lock) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_BROWSER_LOCK))
            finish()
        }
        wire(R.id.menu_tab_retention) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_TAB_RETENTION))
            finish()
        }
        wire(R.id.menu_pull_to_refresh) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_PULL_TO_REFRESH))
            finish()
        }
        wire(R.id.menu_keep_screen_on) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_KEEP_SCREEN_ON))
        }
        wire(R.id.menu_tab_environment) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_TAB_ENVIRONMENT))
            finish()
        }
        findViewById<TextView>(R.id.menu_navigation_lens)?.let {
            wire(R.id.menu_navigation_lens) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_NAVIGATION_LENS))
                finish()
            }
        }
        findViewById<TextView>(R.id.menu_object_anchors)?.let {
            wire(R.id.menu_object_anchors) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_OBJECT_ANCHORS))
                finish()
            }
        }
        findViewById<TextView>(R.id.menu_same_page_split)?.let {
            wire(R.id.menu_same_page_split) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_SAME_PAGE_SPLIT))
                finish()
            }
        }
        findViewById<TextView>(R.id.menu_desktop_mode)?.let { wire(R.id.menu_desktop_mode) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_DESKTOP_MODE)); finish() } }
        findViewById<TextView>(R.id.menu_favorite)?.let { wire(R.id.menu_favorite) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_FAVORITE)); finish() } }
        findViewById<TextView>(R.id.menu_help)?.let { wire(R.id.menu_help) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_HELP)); finish() } }
        wire(R.id.menu_split_screen) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_SPLIT_SCREEN))
            finish()
        }
        wire(R.id.menu_theme) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_THEME))
            finish()
        }
        // Settings row → general accounts & sign-in (Google, Microsoft, Apple, …)
        wire(R.id.menu_settings) {
            startActivityForResult(Intent(this, AccountsActivity::class.java), REQ_NESTED)
        }
        wire(R.id.menu_close) { finish() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val openUrl = data.getStringExtra("open_url")
            ?: data.getStringExtra(AccountsActivity.EXTRA_OPEN_URL)
        if (!openUrl.isNullOrBlank()) {
            setResult(RESULT_OK, Intent().putExtra("open_url", openUrl))
            finish()
        }
    }

    private fun wire(id: Int, action: () -> Unit) {
        findViewById<TextView>(id).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }
}
