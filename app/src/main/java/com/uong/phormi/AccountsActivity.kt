package com.uong.phormi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/**
 * General sign-in hub. Provider pages open in a normal Phormi tab, so the same
 * WebView cookie/session store is used by ordinary browsing.
 */
class AccountsActivity : AppCompatActivity() {
    data class AccountProvider(val id: String, val label: String, val loginUrl: String, val switchUrl: String, val note: String)

    companion object {
        const val EXTRA_OPEN_URL = "open_url"
        val PROVIDERS = listOf(
            AccountProvider("google", "Google", "https://accounts.google.com/", "https://accounts.google.com/AccountChooser", "Some Google sign-ins restrict embedded browsers; open externally if blocked."),
            AccountProvider("microsoft", "Microsoft", "https://account.microsoft.com/", "https://account.microsoft.com/", "Use Microsoft's own account switcher after signing in."),
            AccountProvider("apple", "Apple", "https://appleid.apple.com/sign-in", "https://appleid.apple.com/", "Apple web session."),
            AccountProvider("github", "GitHub", "https://github.com/login", "https://github.com/login", "Developer account."),
            AccountProvider("yahoo", "Yahoo", "https://login.yahoo.com/", "https://login.yahoo.com/", "Yahoo account."),
            AccountProvider("amazon", "Amazon", "https://www.amazon.com/ap/signin", "https://www.amazon.com/ap/signin", "Amazon account."),
            AccountProvider("facebook", "Facebook / Meta", "https://www.facebook.com/login", "https://www.facebook.com/login", "Meta account.")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)
        findViewById<TextView>(R.id.accounts_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.accounts_help).text =
            "Open a website account in a normal tab to keep its cookies in that browsing environment. Some providers block WebView sign-in; long-press a provider for an external browser fallback. External sign-ins cannot be imported into Phormi, and this list does not prove you are signed in."
        render()
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.accounts_list)
        container.removeAllViews()
        val sessions = AccountSessionStore.list(this)
        if (sessions.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "Recently opened providers"
                setTextColor(0xFF38BDF8.toInt()); textSize = 14f; setPadding(12, 16, 12, 8)
            })
            sessions.forEach { session ->
                val row = layoutInflater.inflate(R.layout.item_account, container, false)
                row.findViewById<TextView>(R.id.account_label).text = session.label
                row.findViewById<TextView>(R.id.account_note).text = "Last used ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.lastUsed))} · tap to reopen / switch"
                row.setOnClickListener { PROVIDERS.firstOrNull { it.id == session.providerId }?.let { openProvider(it, true) } }
                row.setOnLongClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("${session.label} provider")
                        .setItems(arrayOf("Open account switcher in Phormi", "Open externally", "Forget from this list")) { _, which ->
                            val provider = PROVIDERS.firstOrNull { it.id == session.providerId } ?: return@setItems
                            when (which) {
                                0 -> openProvider(provider, true)
                                1 -> openExternal(provider)
                                else -> { AccountSessionStore.remove(this, provider.id); render() }
                            }
                        }.show()
                    true
                }
                container.addView(row)
            }
        }
        container.addView(TextView(this).apply {
            text = "Sign in / switch account"
            setTextColor(0xFF38BDF8.toInt()); textSize = 14f; setPadding(12, 18, 12, 8)
        })
        PROVIDERS.forEach { provider ->
            val row = layoutInflater.inflate(R.layout.item_account, container, false)
            row.findViewById<TextView>(R.id.account_label).text = provider.label
            row.findViewById<TextView>(R.id.account_note).text = provider.note
            row.setOnClickListener { openProvider(provider, false) }
            row.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle(provider.label)
                    .setItems(arrayOf("Open in Phormi", "Open in external browser")) { _, which ->
                        if (which == 0) openProvider(provider, false) else openExternal(provider)
                    }.show()
                true
            }
            container.addView(row)
        }
    }

    private fun openExternal(provider: AccountProvider) {
        val url = Uri.parse(provider.loginUrl)
        runCatching {
            androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, url)
        }.onFailure {
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE), "Open account website"))
            }.onFailure { Toast.makeText(this, "No external browser is available", Toast.LENGTH_LONG).show() }
        }
        Toast.makeText(this, "External browser sessions are separate from Phormi tabs", Toast.LENGTH_LONG).show()
    }

    private fun openProvider(provider: AccountProvider, switch: Boolean) {
        val url = if (switch) provider.switchUrl else provider.loginUrl
        AccountSessionStore.touch(this, provider.id, provider.label)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_url", url)
        })
        finish()
    }
}
