package com.uong.phormi

import java.util.Locale

/**
 * Pure desktop-mode policy shared by navigation, tab restore, the menu and tests.
 *
 * There is a global default plus explicit per-host overrides in both directions:
 * a host can opt in while the global default is mobile, or opt out while the
 * global default is desktop. Host matching ignores a leading `www.` and case.
 */
object PhormiDesktopMode {
    const val KEY_GLOBAL = "desktop_mode_all"
    const val KEY_DESKTOP_HOSTS = "desktop_sites"
    const val KEY_MOBILE_HOSTS = "desktop_sites_off"
    const val KEY_POLICY_DIRTY = "desktop_policy_dirty"

    /** Wide enough for common desktop breakpoints (GitHub switches at 1012px). */
    const val DESKTOP_VIEWPORT_WIDTH = 1280

    fun normalizeHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        return host.lowercase(Locale.US).removePrefix("www.").takeIf { it.isNotBlank() }
    }

    fun resolve(
        url: String?,
        globalDesktop: Boolean,
        desktopHosts: Set<String>,
        mobileHosts: Set<String>
    ): Boolean {
        val host = normalizeHost(url) ?: return globalDesktop
        val normalizedDesktop = desktopHosts.map { it.lowercase(Locale.US).removePrefix("www.") }.toSet()
        val normalizedMobile = mobileHosts.map { it.lowercase(Locale.US).removePrefix("www.") }.toSet()
        if (host in normalizedMobile) return false
        if (host in normalizedDesktop) return true
        return globalDesktop
    }

    fun toggleHost(
        host: String,
        enabled: Boolean,
        desktopHosts: Set<String>,
        mobileHosts: Set<String>
    ): Pair<Set<String>, Set<String>> {
        val normalized = host.lowercase(Locale.US).removePrefix("www.")
        val on = desktopHosts.toMutableSet()
        val off = mobileHosts.toMutableSet()
        if (enabled) {
            on += normalized
            off -= normalized
        } else {
            off += normalized
            on -= normalized
        }
        return on to off
    }

    /** Keep the mobile Chrome major version and the desktop platform tokens. */
    fun userAgent(defaultMobileUserAgent: String, enabled: Boolean): String {
        if (!enabled) return defaultMobileUserAgent
        val major = Regex("Chrome/(\\d+)").find(defaultMobileUserAgent)?.groupValues?.getOrNull(1) ?: "120"
        return PhormiBrowserChrome.sanitizeDesktopUserAgent(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
        )
    }

    fun menuLabel(enabled: Boolean): String =
        if (enabled) "▣  Desktop site · On" else "▣  Desktop site · Off"

    /**
     * Late viewport reconciliation for desktop pages. The primary layout switch is
     * still applied before navigation; this only repairs pages whose own viewport
     * meta tag would otherwise force a mobile CSS width after the document loads.
     *
     * The viewport must never be wider than the screen. A 1280px layout viewport
     * scaled down to a 412px phone makes text unreadable and pushes edge controls
     * off-screen - the "desktop mode hides parts of the page" complaint. Rendering
     * at the real width keeps Chrome's behaviour: text stays legible, pinch zoom
     * and the zoom controls remain available, and nothing is clipped.
     */
    fun viewportScript(): String = """
        (function(){
          var dpr = window.devicePixelRatio || 1;
          var width = Math.round((window.screen.width || 980) / dpr);
          var content = 'width=' + width + ', initial-scale=1';
          var m = document.querySelector('meta[name="viewport"]');
          if (!m) { m = document.createElement('meta'); m.name = 'viewport'; document.head && document.head.appendChild(m); }
          if (m) m.setAttribute('content', content);
        })();
    """.trimIndent()

    /**
     * A rotation fires onConfigurationChanged(orientation|screenSize); the WebView
     * keeps the old window width until the new layout pass. Reloading layouts is a
     * one-line reflow, not a page reload, so state, form values and video playback
     * survive turning the phone.
     */
    fun reflowScript(): String = """
        (function(){
          try { window.dispatchEvent(new Event('resize')); } catch (e) {}
          try {
            var vv = window.visualViewport;
            if (vv) vv.dispatchEvent(new Event('resize'));
            if (document.body) document.body.offsetWidth;
          } catch (e) {}
        })();
    """.trimIndent()
}
