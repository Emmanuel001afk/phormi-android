package com.uong.phormi

/**
 * Data saver: an honest, WebView-only subset of what a server-side proxy does.
 *
 * Android's WebView exposes no image downscaling and no compression proxy, so Phormi
 * cannot promise "40% less data" the way Chrome's old server-side Lite mode could. What
 * it can do locally, without sending browsing data to anyone:
 *
 * - prefer the disk cache, and fall back to it when a request fails;
 * - never autoplay media, so video is not fetched before the user taps;
 * - block images before they are decoded, keeping page layout intact;
 * - reload pages with the decoder forced off, which is the only way to apply the
 *   image block to a document whose renderer was created before the mode changed.
 *
 * The scale is Off / Standard / Text only, chosen so each step is visible to the user.
 */
object PhormiDataSaver {
    const val KEY_MODE = "data_saver_mode"
    const val OFF = "off"
    const val STANDARD = "standard"
    const val TEXT_ONLY = "text_only"

    /** Older builds stored this; it must keep meaning "text only", not "off". */
    const val ALWAYS = "always"

    /** Ordered from no saving to maximum local saving. ALWAYS is a legacy alias. */
    val modes = listOf(OFF, STANDARD, TEXT_ONLY)

    fun label(mode: String?): String = when (mode) {
        STANDARD -> "Standard · cache + no autoplay"
        TEXT_ONLY, ALWAYS -> "Text only · images blocked"
        else -> "Off"
    }

    /** One honest line about what the mode does, for the settings row. */
    fun description(mode: String?): String = when (mode) {
        STANDARD ->
            "Reuse cached copies (including a fallback when a request fails) and stop " +
                "audio/video from starting on its own. Images still load."
        TEXT_ONLY, ALWAYS ->
            "Everything in Standard, plus images are blocked before they are decoded. " +
                "Space is kept so the page layout does not collapse."
        else -> "Normal browsing: no cache preference, media autoplay allowed, images load."
    }

    data class Settings(
        val cacheFirst: Boolean,
        val blockImages: Boolean,
        val mediaGestureRequired: Boolean
    )

    fun settings(mode: String?): Settings = when (mode) {
        STANDARD -> Settings(cacheFirst = true, blockImages = false, mediaGestureRequired = true)
        TEXT_ONLY, ALWAYS -> Settings(cacheFirst = true, blockImages = true, mediaGestureRequired = true)
        else -> Settings(cacheFirst = false, blockImages = false, mediaGestureRequired = false)
    }

    /** True when the mode must be replaced by Text only to be applied to a live page. */
    fun isActive(mode: String?): Boolean = mode != null && mode != OFF

    /**
     * Android WebView offers no API to remove a registered document-start script, and
     * an image block cannot be undone in a document that already rendered under it. A
     * reload into a fresh renderer is what applies both changes, so the caller reloads
     * after switching modes. This keeps the decision in one place instead of repeating
     * it wherever the mode is set.
     */
    fun requiresReload(previous: String?, next: String?): Boolean = previous != next

    /**
     * `LOAD_CACHE_ELSE_NETWORK` answers from the disk cache even when the copy has
     * expired, so a page can stay stale indefinitely. That is the trade the user chose,
     * but refresh must still mean refresh: a pull or a tap on ↻ temporarily bypasses
     * the cache instead of re-serving the copy the user is trying to replace.
     *
     * Kept as a boolean decision so this object stays free of Android constants.
     */
    fun preferCache(settings: Settings, userAskedForFreshCopy: Boolean): Boolean =
        settings.cacheFirst && !userAskedForFreshCopy

    /**
     * True when a request must be refused outright so the bytes are never downloaded.
     *
     * `WebSettings.blockNetworkImage` is deprecated and, from API 30, does nothing. Text
     * only therefore relied on a CSS visibility rule: images were hidden but still
     * fetched, which saved layout, not data. Refusing the request in
     * `shouldInterceptRequest` is the only reliable way to stop the download, and it
     * does not need any script to run first.
     *
     * @param mode the current saver mode
     * @param url the request URL
     * @param isMainFrame a document must never be blocked, or the page would not load
     */
    fun blocksImageRequest(mode: String?, url: String?, isMainFrame: Boolean): Boolean {
        if (!settings(mode).blockImages) return false
        if (isMainFrame) return false
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return false
        // Only http(s): data: and blob: images are already local and cost no data.
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return false
        val path = value.substringBefore('#').substringBefore('?').lowercase()
        return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".avif") ||
            path.endsWith(".bmp") || path.endsWith(".ico") || path.endsWith(".svg")
    }

    /**
     * Hides image content before layout instead of removing the elements.
     *
     * `display:none` collapses the page: a news site with a 300px lead image jumps as
     * soon as the block applies. `visibility:hidden` keeps every box at its real size,
     * so the page the user reads is laid out the same way it would be with images
     * present. CSS background images are dropped outright because they carry no layout.
     *
     * This runs in the renderer and touches only the DOM: it makes no network request,
     * so the block itself cannot leak the pages being blocked.
     */
    fun imageBlockScript(): String = """
        (function(){
          if (window.__phormiNoImages) return; window.__phormiNoImages = true;
          var css = 'img,picture,video[poster],source[type^="image"]{visibility:hidden !important}' +
                    '*{background-image:none !important}';
          function inject(){
            try {
              if (document.getElementById('phormi-no-images')) return;
              var s = document.createElement('style');
              s.id = 'phormi-no-images';
              s.textContent = css;
              (document.head || document.documentElement).appendChild(s);
            } catch (e) {}
          }
          inject();
          document.addEventListener('DOMContentLoaded', inject, { once: true });
        })();
    """.trimIndent()
}
