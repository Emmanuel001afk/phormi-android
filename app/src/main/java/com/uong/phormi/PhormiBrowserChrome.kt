package com.uong.phormi

import java.net.URI
import java.util.Locale

/**
 * Pure chrome organisation rules shared by MainActivity, settings copy and regression tests.
 *
 * The layout follows the way Chrome organises a phone address bar, because that is
 * what people already know how to use:
 *
 * ```
 * ‹ ›   [ 🔒 example.com ]   ↻   ⋮
 *  Tabs · Overview · Close · New tab            (from the menu)
 * ```
 *
 * - Back, Forward, the address field, Refresh and Menu are always in one row.
 * - Refresh is permanent: it turns into Stop while a page loads and reloads when it
 *   is idle. It is never hidden just because the screen is narrow.
 * - Refresh shows "empty chrome" instead of a reload arrow on a blank new tab.
 * - The bottom bar is optional and owns Back/Forward when it is enabled; everything
 *   else stays in the top row so nothing is duplicated.
 */
object PhormiBrowserChrome {
    data class State(
        val onHome: Boolean,
        val wide: Boolean,
        /** True when the active tab is blank: about:blank, or every URL cleared. */
        val blankPage: Boolean = onHome,
        val bottomEnabled: Boolean = false,
        val fullscreen: Boolean = false,
        val splitLocked: Boolean = false,
        val activeDownloads: Int = 0
    )

    data class Visibility(
        val top: Boolean,
        val bottom: Boolean,
        val url: Boolean,
        val refresh: Boolean,
        val stop: Boolean,
        val favorite: Boolean,
        val newTab: Boolean,
        val tabStrip: Boolean,
        val topBack: Boolean,
        val topForward: Boolean,
        val topAi: Boolean,
        val topDownloads: Boolean
    )

    fun resolve(state: State): Visibility {
        val chrome = !state.fullscreen && !state.splitLocked
        val browsing = chrome && !state.onHome
        val bottom = browsing && state.bottomEnabled
        // Back/Forward live in the top row on phones. When the optional bottom bar
        // takes ownership of them, the address field gets that width back.
        val navInTop = chrome && !state.bottomEnabled
        // The refresh control is permanent chrome. On a blank tab it is inert.
        val reloadable = browsing && !state.blankPage
        return Visibility(
            top = chrome,
            bottom = bottom,
            url = browsing,
            refresh = chrome,
            stop = reloadable,
            favorite = browsing && state.wide && state.bottomEnabled,
            newTab = chrome,
            tabStrip = chrome && state.wide,
            topBack = navInTop,
            topForward = navInTop,
            // Wide screens also show AI and active downloads inline; the bottom bar does
            // not remove them (it only owns Back/Forward on phones).
            topAi = browsing && state.wide,
            topDownloads = browsing && state.wide && state.activeDownloads > 0
        )
    }

    /** What the omnibox shows for the active tab. */
    fun addressText(url: String?, searchQuery: String?): String {
        if (!searchQuery.isNullOrBlank()) return searchQuery
        val value = url?.trim().orEmpty()
        return if (isBlankUrl(value)) "" else value
    }

    /**
     * An address field that still says "https://youtube.com" after the page was
     * cleared is the "stuck URL" bug. A blank page is about:blank, a local new-tab
     * placeholder, or an empty string.
     */
    fun isBlankUrl(url: String?): Boolean = when (url?.trim()?.lowercase(Locale.ROOT)) {
        null, "", "about:blank", "about:newtab", "chrome://newtab" -> true
        else -> false
    }

    /** Compact host label for tab chips: `www.example.com/path` -> `example.com`. */
    fun tabLabel(title: String?, url: String?): String {
        val name = title?.trim().orEmpty()
        if (name.isNotEmpty() && !name.equals("New tab", true)) return name
        if (isBlankUrl(url)) return "New tab"
        return host(url).ifBlank { name.ifBlank { "New tab" } }
    }

    fun host(url: String?): String = runCatching {
        URI(url.orEmpty()).host.orEmpty()
    }.getOrDefault("").lowercase(Locale.ROOT).removePrefix("www.")

    /**
     * `Chrome/141.0.0.0 Mobile` -> `Chrome/141.0.0.0`. Without this the same WebView
     * reports itself as both `Chrome` and `Mobile` in one UA string, which makes some
     * streaming/site-detection code fall over in desktop mode.
     */
    fun sanitizeDesktopUserAgent(userAgent: String): String {
        val cleaned = userAgent
            .replace("Mobile", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (Regex("Chrome/\\d+").containsMatchIn(cleaned)) cleaned
        else cleaned.ifBlank { "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" }
    }

    fun bottomSummary(): String = "Back · Forward"

    fun topSummary(): String = "Back · Forward · Address · Refresh · Tabs · Menu"

    /** Where the remaining page/tab actions live on a phone. */
    fun menuHomeSummary(): String = "Home · New tab · Close tab · Tab overview · Desktop site"

    fun menuSummary(): String = "Tabs · Tab overview · New tab · Close tab"
}
