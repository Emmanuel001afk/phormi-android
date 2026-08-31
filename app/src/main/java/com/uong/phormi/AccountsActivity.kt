package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/** General sign-in hub. It never stores passwords; provider cookies/sessions stay with the browser. */
class AccountsActivity : AppCompatActivity() {
    data class AccountProvider(val id: String, val label: String, val loginUrl: String, val switchUrl: String, val note: String)

    companion object {
        const val EXTRA_OPEN_URL = "open_url"
        val PROVIDERS = listOf(
            AccountProvider("google", "Google", "https://accounts.google.com/AddSession", "https://accounts.google.com/AccountChooser", "Multiple Google accounts are supported by Google's account chooser."),
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
            "General sign-in. Phormi keeps provider sessions in its browser cookies, so after a successful sign-in you can return without retyping credentials. Sign out remains controlled by the provider."
        render()
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.accounts_list)
        container.removeAllViews()
        val sessions = AccountSessionStore.list(this)
        if (sessions.isNotEmpty()) {
            val heading = TextView(this).apply {
                text = "Previously used accounts"
                setTextColor(0xFF38BDF8.toInt())
                textSize = 14f
                setPadding(12, 16, 12, 8)
            }
            container.addView(heading)
            sessions.forEach { session ->
                val row = layoutInflater.inflate(R.layout.item_account, container, false)
                row.findViewById<TextView>(R.id.account_label).text = session.label
                row.findViewById<TextView>(R.id.account_note).text = "Last used ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.lastUsed))} · tap to switch"
                row.setOnClickListener {
                    val p = PROVIDERS.firstOrNull { it.id == session.providerId }
                    if (p != null) openProvider(p, true)
                }
                row.setOnLongClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("${session.label} account")
                        .setItems(arrayOf("Switch account", "Sign out / clear saved session")) { _, which ->
                            val provider = PROVIDERS.firstOrNull { it.id == session.providerId } ?: return@setItems
                            if (which == 0) {
                                openProvider(provider, true)
                            } else {
                                clearProviderSession(provider)
                            }
                        }.show()
                    true
                }
                container.addView(row)
            }
        }
        val heading = TextView(this).apply {
            text = "Sign in / switch account"
            setTextColor(0xFF38BDF8.toInt())
            textSize = 14f
            setPadding(12, 18, 12, 8)
        }
        container.addView(heading)
        PROVIDERS.forEach { provider ->
            val row = layoutInflater.inflate(R.layout.item_account, container, false)
            row.findViewById<TextView>(R.id.account_label).text = provider.label
            row.findViewById<TextView>(R.id.account_note).text = provider.note
            row.setOnClickListener { openProvider(provider, false) }
            container.addView(row)
        }
    }


    private fun clearProviderSession(provider: AccountProvider) {
        val hosts = when (provider.id) {
            "google" -> listOf("accounts.google.com", "google.com")
            "microsoft" -> listOf("account.microsoft.com", "login.live.com", "microsoft.com")
            "apple" -> listOf("appleid.apple.com", "apple.com")
            "github" -> listOf("github.com")
            "yahoo" -> listOf("login.yahoo.com", "yahoo.com")
            "amazon" -> listOf("amazon.com", "amazon.co.uk", "amazon.ca")
            "facebook" -> listOf("facebook.com")
            else -> emptyList()
        }
        val cookies = android.webkit.CookieManager.getInstance()
        hosts.forEach { host ->
            val raw = cookies.getCookie("https://$host").orEmpty()
            raw.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotBlank() }.forEach { name ->
                cookies.setCookie("https://$host", "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
            }
        }
        cookies.flush()
        AccountSessionStore.remove(this, provider.id)
        Toast.makeText(this, "${provider.label} session cleared. Provider sign-out may also require its own account page.", Toast.LENGTH_LONG).show()
        render()
    }

    private fun openProvider(provider: AccountProvider, switch: Boolean) {
        AccountSessionStore.touch(this, provider.id, provider.label)
        val url = if (switch) provider.switchUrl else provider.loginUrl
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_URL, url))
        Toast.makeText(this, if (switch) "Opening ${provider.label} account switcher" else "Opening ${provider.label} sign-in", Toast.LENGTH_SHORT).show()
        finish()
    }
}
