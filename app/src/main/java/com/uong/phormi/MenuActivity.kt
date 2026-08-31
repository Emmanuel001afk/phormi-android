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
        const val ACTION_KEEP_SCREEN_ON = "keep_screen_on"
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
        wire(R.id.menu_theme) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_THEME))
            finish()
        }
        wire(R.id.menu_keep_screen_on) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, ACTION_KEEP_SCREEN_ON))
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
