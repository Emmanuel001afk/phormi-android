package com.uong.phormi

/**
 * Data saver is an honest, WebView-only subset of what a server-side proxy does.
 *
 * Android's WebView does not expose image downscaling or a compression proxy, so
 * Phormi cannot promise "40% less data" the way Chrome's server-side Lite mode
 * could. What it can do locally, without sending browsing data to anyone:
 *
 * - always use the disk cache and stay on the cached copy while offline;
 * - never auto-play media, and require a tap before audio/video starts;
 * - block images at the renderer level when the user picks "Text only";
 * - keep the existing local search results (no third-party requests).
 *
 * The setting is a three-level scale so the user can see exactly what is traded:
 * Off / Standard / Text only.
 */
object PhormiDataSaver {
    const val KEY_MODE = "data_saver_mode"
    const val OFF = "off"
    const val STANDARD = "standard"
    const val TEXT_ONLY = "text_only"
    const val ALWAYS = "always"

    /** Ordered from no saving to maximum local saving. ALWAYS is a legacy alias. */
    val modes = listOf(OFF, STANDARD, TEXT_ONLY)

    fun label(mode: String?): String = when (mode) {
        STANDARD -> "Standard · cache + no autoplay"
        TEXT_ONLY -> "Text only · images blocked"
        else -> "Off"
    }

    /** User-facing explanation of what each mode does, shown in the chooser. */
    fun description(mode: String?): String = when (mode) {
        STANDARD ->
            "Reuse cached copies of pages and files, keep the last copy available when the " +
                "connection drops, and stop audio/video from starting on its own."
        TEXT_ONLY ->
            "Everything in Standard, plus images are blocked before they are downloaded. " +
                "Layout is preserved, so a page never collapses when an image is skipped."
        else -> "Normal browsing: no cache preference, media autoplay allowed, images load."
    }

    data class Settings(
        val cacheFirst: Boolean,
        val blockImages: Boolean,
        val mediaGestureRequired: Boolean,
        val offlineCache: Boolean
    )

    fun settings(mode: String?): Settings = when (mode) {
        STANDARD -> Settings(cacheFirst = true, blockImages = false, mediaGestureRequired = true, offlineCache = true)
        TEXT_ONLY -> Settings(cacheFirst = true, blockImages = true, mediaGestureRequired = true, offlineCache = true)
        // Older builds stored "always"; treat it as text-only rather than silently ignoring it.
        ALWAYS -> Settings(cacheFirst = true, blockImages = true, mediaGestureRequired = true, offlineCache = true)
        else -> Settings(cacheFirst = false, blockImages = false, mediaGestureRequired = false, offlineCache = false)
    }

    /** Removes `<img>`, CSS background images and picture sources before layout. */
    fun imageBlockScript(): String = """
        (function(){
          if (window.__phormiNoImages) return; window.__phormiNoImages = true;
          var css = 'img,picture,video[poster],source[type^="image"]{visibility:hidden !important}' +
                    '*{background-image:none !important}';
          function inject(){
            try {
              var s = document.createElement('style'); s.textContent = css;
              (document.head || document.documentElement).appendChild(s);
            } catch (e) {}
          }
          inject();
          document.addEventListener('DOMContentLoaded', inject, { once: true });
        })();
    """.trimIndent()
}
