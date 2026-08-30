package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * General account sign-in — not Google-only.
 *
 * Opens the real provider login page inside Phormi WebView (MainActivity tab).
 * Cookies stay on the device so Google / Microsoft / Apple / GitHub / etc.
 * sessions work the same way a normal browser does after you sign in once.
 *
 * These are identity platforms (accounts), not the same category as Android/iOS
 * (operating systems). Google and Microsoft are account/identity services.
 */
class AccountsActivity : AppCompatActivity() {

    data class AccountProvider(
        val id: String,
        val label: String,
        val loginUrl: String,
        val note: String
    )

    companion object {
        const val EXTRA_OPEN_URL = "open_url"
        val PROVIDERS = listOf(
            AccountProvider(
                "google",
                "Google account",
                "https://accounts.google.com/AddSession",
                "Gmail, YouTube, Drive, Play"
            ),
            AccountProvider(
                "microsoft",
                "Microsoft account",
                "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=00000000-0000-0000-0000-000000000000&response_type=code&redirect_uri=https%3A%2F%2Flogin.microsoftonline.com%2Fcommon%2Foauth2%2Fnativeclient&scope=openid",
                "Outlook, Xbox, OneDrive — or use account.microsoft.com"
            ),
            AccountProvider(
                "microsoft_web",
                "Microsoft (web login)",
                "https://account.microsoft.com/",
                "Simple browser sign-in page"
            ),
            AccountProvider(
                "apple",
                "Apple ID",
                "https://appleid.apple.com/sign-in",
                "iCloud / Apple services on the web"
            ),
            AccountProvider(
                "github",
                "GitHub",
                "https://github.com/login",
                "Developer account"
            ),
            AccountProvider(
                "yahoo",
                "Yahoo",
                "https://login.yahoo.com/",
                "Yahoo Mail and services"
            ),
            AccountProvider(
                "amazon",
                "Amazon",
                "https://www.amazon.com/ap/signin",
                "Amazon account"
            ),
            AccountProvider(
                "facebook",
                "Facebook / Meta",
                "https://www.facebook.com/login",
                "Facebook account"
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)

        findViewById<TextView>(R.id.accounts_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.accounts_help).text =
            "Sign-in uses the same browser cookies as Phormi tabs. " +
                "After you sign in once, sites under that account recognise you. " +
                "This is general account sign-in (Google, Microsoft, Apple, …), " +
                "not limited to one brand."

        val container = findViewById<android.widget.LinearLayout>(R.id.accounts_list)
        container.removeAllViews()
        PROVIDERS.forEach { p ->
            val row = layoutInflater.inflate(R.layout.item_account, container, false)
            row.findViewById<TextView>(R.id.account_label).text = p.label
            row.findViewById<TextView>(R.id.account_note).text = p.note
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener {
                val url = if (p.id == "microsoft") {
                    // Prefer the stable account portal over a stub OAuth client id
                    "https://account.microsoft.com/"
                } else p.loginUrl
                setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_URL, url))
                Toast.makeText(this, "Opening ${p.label}", Toast.LENGTH_SHORT).show()
                finish()
            }
            container.addView(row)
        }
    }
}
