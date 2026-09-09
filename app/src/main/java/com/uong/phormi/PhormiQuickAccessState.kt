package com.uong.phormi

import android.content.Context

/**
 * Quick Access visibility is independent from the source item.
 * Hiding a Favorite or Most Visited entry here never deletes the Favorite or history.
 */
object PhormiQuickAccessState {
    private const val PREFS = "phormi_quick_access"
    private const val HIDDEN_FAVORITES = "hidden_favorites"
    private const val HIDDEN_VISITED = "hidden_visited"
    private const val HIDDEN_CUSTOM = "hidden_custom"

    private fun set(context: Context, key: String): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet()).orEmpty().toMutableSet()

    private fun hide(context: Context, key: String, value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        val values = set(context, key)
        if (values.add(clean)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(key, values).apply()
        }
    }

    private fun unhide(context: Context, key: String, value: String) {
        val values = set(context, key)
        if (values.remove(value.trim())) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(key, values).apply()
        }
    }

    fun isFavoriteHidden(context: Context, url: String): Boolean =
        set(context, HIDDEN_FAVORITES).contains(url.trim())

    fun isVisitedHidden(context: Context, urlOrHost: String): Boolean =
        set(context, HIDDEN_VISITED).contains(urlOrHost.trim())

    fun isCustomHidden(context: Context, url: String): Boolean =
        set(context, HIDDEN_CUSTOM).contains(url.trim())

    fun hideFavorite(context: Context, url: String) = hide(context, HIDDEN_FAVORITES, url)
    fun hideVisited(context: Context, urlOrHost: String) = hide(context, HIDDEN_VISITED, urlOrHost)
    fun hideCustom(context: Context, url: String) = hide(context, HIDDEN_CUSTOM, url)

    fun restoreFavorite(context: Context, url: String) = unhide(context, HIDDEN_FAVORITES, url)
    fun restoreVisited(context: Context, urlOrHost: String) = unhide(context, HIDDEN_VISITED, urlOrHost)
    fun restoreCustom(context: Context, url: String) = unhide(context, HIDDEN_CUSTOM, url)

    fun clearForSourceRemoval(context: Context, url: String) {
        restoreFavorite(context, url)
        restoreVisited(context, url)
        restoreCustom(context, url)
    }
}
