package com.uong.phormi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import java.text.DateFormat
import java.util.Date

/**
 * General third-party sign-in hub. Provider authentication is opened in a secure
 * Custom Tab rather than an embedded WebView. Provider sessions remain controlled
 * by the provider; Phormi does not copy cookies between browsers or environments.
 */
class AccountsActivity : AppCompatActivity() {
    data class AccountProvider(val id: String, val label: String, val loginUrl: String, val switchUrl: String, val note: String)

    companion object {
        const val EXTRA_OPEN_URL = "open_url"
        val PROVIDERS = listOf(
            AccountProvider("google", "Google", "https://accounts.google.com/", "https://accounts.google.com/AccountChooser", "Secure browser sign-in. Google authentication is handled by Google's own browser surface."),
            AccountProvider("microsoft", "Microsoft", "https://account.microsoft.com/", "https://account.microsoft.com/", "Use Microsoft's own account switcher after signing in."),
            AccountProvider("apple", "Apple", "https://appleid.apple.com/sign-in", "https://appleid.apple.com/", "Apple web session."),
            AccountProvider("github", "GitHub", "https://github.com/login", "https://github.com/login", "Developer account."),
            AccountProvider("yahoo", "Yahoo", "https://login.yahoo.com/", "https://login.yahoo.com/", "Yahoo account."),
            AccountProvider("amazon", "Amazon", "https://www.amazon.com/ap/signin", "https://www.amazon.com/ap/signin", "Amazon account."),
            AccountProvider("facebook", "Facebook / Meta", "https://www.facebook.com/login", "https://www.facebook.com/login", "Meta account.")
        )
    }

    private var pendingProvider: AccountProvider? = null
    private var authTabWasPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)
        findViewById<TextView>(R.id.accounts_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.accounts_help).text =
            "Third-party sign-in opens in a secure Custom Tab and returns here instead of creating a normal Phormi tab. When you return, Phormi asks whether to remember that you trusted the provider on this device. Provider authentication itself is never copied or fabricated by Phormi."
        render()
    }

    override fun onPause() {
        if (pendingProvider != null) authTabWasPaused = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (authTabWasPaused && pendingProvider != null) {
            authTabWasPaused = false
            val provider = pendingProvider ?: return
            pendingProvider = null
            confirmReturnedFromAuth(provider)
        }
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.accounts_list)
        container.removeAllViews()
        val sessions = AccountSessionStore.list(this)
        if (sessions.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "Previously trusted providers"
                setTextColor(0xFF38BDF8.toInt()); textSize = 14f; setPadding(12, 16, 12, 8)
            })
            sessions.forEach { session ->
                val row = layoutInflater.inflate(R.layout.item_account, container, false)
                row.findViewById<TextView>(R.id.account_label).text = session.label
                row.findViewById<TextView>(R.id.account_note).text = "Last used ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.lastUsed))} · tap to switch"
                row.setOnClickListener { PROVIDERS.firstOrNull { it.id == session.providerId }?.let { openProvider(it, true) } }
                row.setOnLongClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("${session.label} provider")
                        .setItems(arrayOf("Switch account", "Forget trusted provider")) { _, which ->
                            val provider = PROVIDERS.firstOrNull { it.id == session.providerId } ?: return@setItems
                            if (which == 0) openProvider(provider, true) else {
                                AccountSessionStore.remove(this, provider.id)
                                render()
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
            container.addView(row)
        }
    }

    private fun confirmReturnedFromAuth(provider: AccountProvider) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Trust ${provider.label} on this device?")
            .setMessage("Phormi cannot safely infer whether an arbitrary provider login succeeded. Confirm only if you completed the provider sign-in successfully.")
            .setNegativeButton("Not yet", null)
            .setPositiveButton("Trust this device") { _, _ ->
                AccountSessionStore.touch(this, provider.id, provider.label)
                Toast.makeText(this, "${provider.label} marked as trusted on this device", Toast.LENGTH_SHORT).show()
                render()
            }
            .show()
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
        pendingProvider = provider
        authTabWasPaused = false
        val url = if (switch) provider.switchUrl else provider.loginUrl
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
                .launchUrl(this, Uri.parse(url))
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure {
                    pendingProvider = null
                    Toast.makeText(this, "No browser can open ${provider.label} sign-in", Toast.LENGTH_LONG).show()
                }
        }
    }
}
