package com.uong.phormi

/** Pure chrome organisation rules shared by MainActivity, settings copy and regression tests. */
object PhormiBrowserChrome {
    data class State(
        val onHome: Boolean,
        val wide: Boolean,
        val bottomEnabled: Boolean,
        val fullscreen: Boolean = false,
        val splitLocked: Boolean = false,
        val activeDownloads: Int = 0
    )

    data class Visibility(
        val top: Boolean,
        val bottom: Boolean,
        val home: Boolean,
        val url: Boolean,
        val refresh: Boolean,
        val favorite: Boolean,
        val newTab: Boolean,
        val tabStrip: Boolean,
        val topBack: Boolean,
        val topForward: Boolean,
        val topAi: Boolean,
        val topDownloads: Boolean
    )

    /**
     * One horizontal layer by default. When the optional bottom bar is off, the top
     * bar carries the essential page navigation (Home/Back/Forward) plus address,
     * tabs and menu. AI/downloads stay in the menu on phones; wide screens may show
     * them inline. When the bottom bar is on, it owns Back/Forward/AI/Downloads and
     * the top bar gives that width back to the address field.
     */
    fun resolve(state: State): Visibility {
        val chrome = !state.fullscreen && !state.splitLocked
        val browsing = chrome && !state.onHome
        val bottom = browsing && state.bottomEnabled
        val navInTop = browsing && !state.bottomEnabled
        return Visibility(
            top = chrome,
            bottom = bottom,
            home = browsing,
            url = browsing,
            refresh = browsing && (state.wide || state.bottomEnabled),
            favorite = browsing && state.wide && state.bottomEnabled,
            newTab = chrome && (state.onHome || state.wide),
            tabStrip = chrome && state.wide,
            topBack = navInTop,
            topForward = navInTop,
            topAi = browsing && !state.bottomEnabled && state.wide,
            topDownloads = browsing && !state.bottomEnabled && state.wide && state.activeDownloads > 0
        )
    }

    fun bottomSummary(): String =
        "Back · Forward · AI · Downloads"

    fun topWithoutBottomSummary(): String =
        "Home · Back · Forward · Address · Tabs · Menu"
}
