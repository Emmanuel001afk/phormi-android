package com.uong.phormi

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** In-product Help for Phormi's normal browser foundation and Phormi-specific layers. */
class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF08101C.toInt())
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(0xFF0F172A.toInt())
        }
        bar.addView(TextView(this).apply {
            text = "‹  Phormi Help"
            textSize = 20f
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(4.dp(), 8.dp(), 16.dp(), 8.dp())
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(bar)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 18.dp(), 18.dp(), 30.dp())
        }
        section(body, "Normal browser", "Phormi keeps the normal browser foundation: tabs, tab overview, back and forward navigation, refresh, address/search input, bookmarks/favorites, history, downloads, find in page, sharing, desktop mode, permissions, media/file selection, geolocation, authentication, security controls and private browsing.")
        section(body, "Tabs", "Each tab owns one WebView and keeps its own page state, title, navigation history and tab metadata. Normal tabs share the default browsing environment so websites can keep normal sign-in and cookie behavior. Tabs can be created, selected, closed and restored after the app is recreated.")
        section(body, "Tab groups + Split Screen", "Groups organize related tabs. Split Screen reuses existing tab WebViews in two pane hosts rather than creating a second browser engine. Each pane can hold a different tab, and the divider can be resized or locked. Same-page Split creates two real tabs for the same URL so their scroll positions remain independent.")
        section(body, "Identity environments", "Normal tabs remain shared by default. When the installed WebView supports multiple profiles, a tab can be assigned to Default or a named environment with separate website storage and cookies. This is intended for cases such as the same site signed into two different accounts. Phormi does not label tabs as isolated when the WebView cannot actually provide that isolation.")
        section(body, "Ghost mode", "Ghost browsing is isolated from ordinary browser session persistence. Ghost tabs are not saved into the normal tab list or history, and their temporary browsing environment is cleaned up when Ghost browsing closes.")
        section(body, "Navigation", "Use the address bar for a URL or search. The top ↻ button refreshes the active tab; pull-to-refresh is off by default to avoid accidental reloads. Browser settings lets you hide the bottom controls for more page space; Back, Forward and AI stay available from the top/menu. Text size can be changed without affecting other apps. Incoming web links can open in Phormi when Android assigns it the browser role.")
        section(body, "Search", "Phormi Search merges independent sources and keeps each tab's search results separate. A failed source does not cancel the others. Choose another provider from the start page when you prefer an ordinary single-provider search.")
        section(body, "Bookmarks, favorites and history", "Bookmarks and Favorites are separate browser collections. The favorite control saves the current page to Favorites, while bookmarks use the bookmark control. History records normal page visits and powers the dedicated History section. Ghost browsing is excluded from normal history. Quick Access contains only shortcuts that you manually add or pin; Favorites and Most Visited are not automatically inserted.")
        section(body, "Downloads & files", "The download badge shows when files are in progress, even with the bottom bar hidden. The Downloads screen shows a running count and progress (or an indeterminate bar when size is unknown). Normal downloads use Phormi's resumable in-app transfer engine while legacy Android DownloadManager items are still shown in the list. Active downloads can be paused, resumed, retried or cancelled. Images and supported video/audio open in Phormi first; files without a built-in opener are handed to compatible installed Android applications. Download handling preserves the page's user agent, referrer and available cookies when appropriate.")
        section(body, "Find and Share", "Find in Page uses the active WebView's native search capability. Share sends the current page through Android's normal sharing mechanism, so Phormi does not need to invent a separate sharing system.")
        section(body, "Permissions and media", "Camera, microphone and geolocation requests are handled asynchronously and are granted only when the requesting website asks for them. Web file choosers support single or multiple selections where the site requests them. Media playback supports fullscreen and Picture-in-Picture where Android and the content permit it.")
        section(body, "Security & privacy", "TLS certificate errors are failed closed and Safe Browsing threats are sent back to safety. The Security Center controls JavaScript, third-party cookies and mixed HTTP content, plus browser/site locking and local site-data clearing. These controls are designed to change actual browser behavior rather than merely display labels.")
        section(body, "Browser Lock", "Browser Lock protects access when Phormi is left and reopened. Device security or a local Phormi PIN can be used. Site locks add a more granular protection layer without forcing authentication for every normal tab switch.")
        section(body, "Keyboard", "Phormi Keyboard is an Android input method with a fixed-height suggestion strip, offline dictionaries, conservative automatic corrections and undo-by-backspace. If you retype a corrected spelling it stops correcting that word. The ABC toolbar opens Symbols directly; the number page has a single Symbols key. Shift is purple and caps lock red. Emoji are grouped with Recent first; contextual AI emoji insert Unicode text, not images. Optional image stickers live separately in Media. Clipboard items show a Delete key; deleting all history needs confirmation. Password fields disable predictions and contextual emoji.")
        section(body, "AI", "AI is optional. Configure a provider to queue browser tasks that can inspect and act across normal tabs without replacing your visible tab; sensitive form submissions require approval. The AI screen shows task updates and lets you choose a starting tab. Scheduled instructions set reminders, then require your approval in the foreground; Phormi does not browse unattended in the background. Browser navigation works without AI. A separate web fallback opens supported AI services in normal tabs.")
        section(body, "Navigation Lens + Object Anchors", "Navigation Lens inspects the current page's DOM through the existing WebView and exposes useful headings, links, buttons and images. Object Anchors save lightweight page-object metadata so Phormi can try to relocate the object later without copying the page itself.")
        section(body, "Accounts and sign-in", "Normal tabs in the same environment share cookies but not page/navigation state. Some providers, including Google, restrict embedded WebView authentication. In Accounts, long-press a provider for an external browser fallback; external browser sessions do not become Phormi sessions. Recently opened providers are shortcuts, not proof of a successful sign-in.")
        section(body, "Tab retention and performance", "Tab retention offers 1 month, 3 months, 1 year, or Off/Never and automatically prunes normal tabs that have been inactive for the selected period. Session restoration stores tab metadata and bounded WebView state so the browser can recover navigation context after process recreation. Expensive search, favicon and AI work is kept away from the main interaction path where possible, and renderer crashes use bounded recovery rather than an immediate infinite reload loop.")
        section(body, "Keep Screen On", "Keep Screen On is a normal browser utility. When enabled, Phormi uses Android's window FLAG_KEEP_SCREEN_ON so the screen remains awake while you work in the browser. The preference is persisted and reapplied when Phormi resumes.")
        section(body, "Central Hub", "Central Hub communication remains a separate local coordination layer. Phormi exposes browser-state and viewport commands through its existing bridge without turning Central Hub into the browser itself.")
        section(body, "What Phormi does not fake", "Cross-device continuity requires a real authenticated transport and is not represented as a fake button. Full website Web Push requires a real background transport and is not substituted with ordinary Android notifications. Local AI through WebGPU is only possible when the installed WebView/device actually reports the required capabilities.")
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun section(parent: LinearLayout, titleText: String, bodyText: String) {
        parent.addView(TextView(this).apply {
            text = titleText
            textSize = 18f
            setTextColor(0xFFF8FAFC.toInt())
            setPadding(0, 12.dp(), 0, 5.dp())
        })
        parent.addView(TextView(this).apply {
            text = bodyText
            textSize = 14f
            setTextColor(0xFFCBD5E1.toInt())
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, 10.dp())
        })
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
