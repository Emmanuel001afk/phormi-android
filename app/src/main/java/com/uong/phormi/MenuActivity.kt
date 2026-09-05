package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
        const val ACTION_BOOKMARKS = "bookmarks"
        const val ACTION_HISTORY = "history"
        const val ACTION_ACCOUNTS = "accounts"
        const val ACTION_DESKTOP_MODE = "desktop_mode"
        const val ACTION_FAVORITE = "favorite"
        const val ACTION_FIND = "find"
        const val ACTION_HELP = "help"
        const val ACTION_KEEP_SCREEN_ON = "keep_screen_on"
        const val ACTION_NAVIGATION_LENS = "navigation_lens"
        const val ACTION_NOTIFICATIONS = "notifications"
        const val ACTION_OBJECT_ANCHORS = "object_anchors"
        const val ACTION_SAME_PAGE_SPLIT = "same_page_split"
        const val ACTION_SECURITY = "security"
        const val ACTION_SHARE = "share"
        const val ACTION_SITE_LOCK = "site_lock"
        const val ACTION_TAB_ENVIRONMENT = "tab_environment"
        const val ACTION_KEYBOARD = "keyboard"
        const val ACTION_DEFAULT_BROWSER = "default_browser"
        const val ACTION_TAB_GROUPS = "tab_groups"
        const val ACTION_VIDEO_ANALYSIS = "video_analysis"
        private const val REQ_NESTED = 2100
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)
        wire(R.id.menu_new_tab) { finishWith(ACTION_NEW_TAB) }
        wire(R.id.menu_ghost) { finishWith(ACTION_GHOST) }
        wire(R.id.menu_tabs) { startActivity(Intent(this, TabsOverviewActivity::class.java)) }
        wire(R.id.menu_groups) { startActivity(Intent(this, TabGroupsActivity::class.java)) }
        wire(R.id.menu_downloads) { startActivity(Intent(this, DownloadsActivity::class.java)) }
        wire(R.id.menu_bookmarks) { startActivityForResult(Intent(this, BookmarksActivity::class.java), REQ_NESTED) }
        wire(R.id.menu_history) { startActivityForResult(Intent(this, HistoryActivity::class.java), REQ_NESTED) }
        wire(R.id.menu_vpn) { startActivity(Intent(this, VpnActivity::class.java)) }
        wire(R.id.menu_ai) { startActivity(Intent(this, AiActivity::class.java)) }
        wire(R.id.menu_video_analysis) { finishWith(ACTION_VIDEO_ANALYSIS) }
        wire(R.id.menu_keyboard) { PhormiKeyboardController.showKeyboardPicker(this) }
        wire(R.id.menu_default_browser) { PhormiDefaultBrowserController.request(this) }
        wire(R.id.menu_browser_lock) { finishWith(ACTION_BROWSER_LOCK) }
        wire(R.id.menu_security) { startActivity(Intent(this, PhormiSecurityCenterActivity::class.java)) }
        wire(R.id.menu_site_lock) { finishWith(ACTION_SITE_LOCK) }
        wire(R.id.menu_notifications) {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            runCatching { startActivity(i) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        wire(R.id.menu_find) { finishWith(ACTION_FIND) }
        wire(R.id.menu_share) { finishWith(ACTION_SHARE) }
        wire(R.id.menu_navigation_lens) { finishWith(ACTION_NAVIGATION_LENS) }
        wire(R.id.menu_object_anchors) { finishWith(ACTION_OBJECT_ANCHORS) }
        wire(R.id.menu_same_page_split) { finishWith(ACTION_SAME_PAGE_SPLIT) }
        wire(R.id.menu_desktop_mode) { finishWith(ACTION_DESKTOP_MODE) }
        wire(R.id.menu_favorite) { finishWith(ACTION_FAVORITE) }
        wire(R.id.menu_keep_screen_on) { finishWith(ACTION_KEEP_SCREEN_ON) }
        wire(R.id.menu_help) { finishWith(ACTION_HELP) }
        wire(R.id.menu_tab_retention) { finishWith(ACTION_TAB_RETENTION) }
        wire(R.id.menu_pull_to_refresh) { finishWith(ACTION_PULL_TO_REFRESH) }
        wire(R.id.menu_split_screen) { finishWith(ACTION_SPLIT_SCREEN) }
        wire(R.id.menu_theme) { finishWith(ACTION_THEME) }
        wire(R.id.menu_settings) { startActivityForResult(Intent(this, AccountsActivity::class.java), REQ_NESTED) }
        wire(R.id.menu_close) { finish() }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val openUrl = data.getStringExtra("open_url") ?: data.getStringExtra(AccountsActivity.EXTRA_OPEN_URL)
        if (!openUrl.isNullOrBlank()) { setResult(RESULT_OK, Intent().putExtra("open_url", openUrl)); finish() }
    }
    private fun finishWith(action: String) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action)); finish() }
    private fun wire(id: Int, action: () -> Unit) { findViewById<TextView>(id)?.apply { isClickable=true; isFocusable=true; setOnClickListener { action() } } }
}
