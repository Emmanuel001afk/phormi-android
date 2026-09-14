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
 * Account hub. Normal mode uses Phormi's browser WebView session. Phormi never
 * collects or stores a provider password; the provider's own page handles it.
 */
class AccountsActivity : AppCompatActivity() {
    data class AccountProvider(val id: String, val label: String, val loginUrl: String, val switchUrl: String, val note: String)

    companion object {
        const val EXTRA_OPEN_URL = "open_url"
        val PROVIDERS = listOf(
            AccountProvider("google", "Google", "https://accounts.google.com/", "https://accounts.google.com/AccountChooser", "Normal browser session or separate provider session."),
            AccountProvider("microsoft", "Microsoft", "https://login.live.com/", "https://account.microsoft.com/", "Normal browser session or separate provider session."),
            AccountProvider("apple", "Apple", "https://appleid.apple.com/sign-in", "https://appleid.apple.com/", "Normal browser session or separate provider session."),
            AccountProvider("github", "GitHub", "https://github.com/login", "https://github.com/login", "Normal browser session or separate provider session."),
            AccountProvider("yahoo", "Yahoo", "https://login.yahoo.com/", "https://login.yahoo.com/", "Normal browser session or separate provider session."),
            AccountProvider("amazon", "Amazon", "https://www.amazon.com/ap/signin", "https://www.amazon.com/ap/signin", "Normal browser session or separate provider session."),
            AccountProvider("facebook", "Facebook / Meta", "https://www.facebook.com/login", "https://www.facebook.com/login", "Normal browser session or separate provider session.")
        )
    }

    private var pendingProvider: AccountProvider? = null
    private var authTabWasPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)
        findViewById<TextView>(R.id.accounts_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.accounts_help).text =
            "Normal browser sign-in uses Phormi's current browser environment. Sign in on the provider's own page; compatible websites in that environment can then recognize the same session. Separate provider sign-in opens an isolated provider browser surface. Phormi never asks for or stores your provider password."
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
                text = "Browser sessions"
                setTextColor(0xFF38BDF8.toInt())
                textSize = 14f
                setPadding(12, 16, 12, 8)
            })
            sessions.forEach { session ->
                val row = layoutInflater.inflate(R.layout.item_account, container, false)
                row.findViewById<TextView>(R.id.account_label).text = session.label
                row.findViewById<TextView>(R.id.account_note).text = "Last used ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.lastUsed))} · tap to switch"
                row.setOnClickListener { PROVIDERS.firstOrNull { it.id == session.providerId }?.let { showModeChooser(it, true) } }
                row.setOnLongClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("${session.label} session")
                        .setItems(arrayOf("Switch account", "Forget session record")) { _, which ->
                            val provider = PROVIDERS.firstOrNull { it.id == session.providerId } ?: return@setItems
                            if (which == 0) showModeChooser(provider, true)
                            else {
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
            setTextColor(0xFF38BDF8.toInt())
            textSize = 14f
            setPadding(12, 18, 12, 8)
        })
        PROVIDERS.forEach { provider ->
            val row = layoutInflater.inflate(R.layout.item_account, container, false)
            row.findViewById<TextView>(R.id.account_label).text = provider.label
            row.findViewById<TextView>(R.id.account_note).text = provider.note
            row.setOnClickListener { showModeChooser(provider, false) }
            container.addView(row)
        }
    }

    private fun showModeChooser(provider: AccountProvider, switching: Boolean) {
        val actions = arrayOf("Normal browser sign-in", "Separate provider sign-in")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (switching) "Switch ${provider.label} account" else "Sign in to ${provider.label}")
            .setMessage("Normal browser sign-in uses this browser's current environment. Separate provider sign-in keeps authentication outside Phormi's normal WebView session.")
            .setItems(actions) { _, which ->
                if (which == 0) openNormalBrowserSignIn(provider)
                else openProvider(provider, true)
            }
            .show()
    }

    private fun openNormalBrowserSignIn(provider: AccountProvider) {
        // MainActivity owns the normal WebView profile and its cookies. Do not use
        // Custom Tabs here: their cookie/session boundary is what caused the old
        // "general sign-in" flow not to carry into websites opened in Phormi.
        val target = if (provider.id == "google") provider.switchUrl else provider.loginUrl
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(target)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        AccountSessionStore.touch(this, provider.id, provider.label)
        finish()
    }

    private fun confirmReturnedFromAuth(provider: AccountProvider) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Remember ${provider.label} session?")
            .setMessage("Phormi can remember the provider session label on this device. Your provider password is never stored by Phormi.")
            .setNegativeButton("Not yet", null)
            .setPositiveButton("Remember") { _, _ ->
                AccountSessionStore.touch(this, provider.id, provider.label)
                Toast.makeText(this, "${provider.label} session remembered", Toast.LENGTH_SHORT).show()
                render()
            }
            .show()
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
