package com.uong.phormi

import android.Manifest
import android.content.Context
import java.util.Locale
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.URI

/** Android app permission never substitutes for consent to a specific website.
 * Media/private-mode grants are one-shot; normal location choices may be remembered explicitly. */
class PhormiSitePermissions(private val activity: Activity, private val isActive: (WebView) -> Boolean, private val onAccessGranted: (WebView) -> Unit = {}, private val locationStore: Store? = null) {
    interface Store {
        fun read(origin: String): String?
        fun write(origin: String, value: String?)
        fun clear()
    }

    class SharedPreferencesStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("phormi_site_permissions", Context.MODE_PRIVATE)
        private fun key(origin: String) = "geolocation|$origin"

        override fun read(origin: String): String? =
            if (origin.isBlank()) null else prefs.getString(key(origin), null)

        override fun write(origin: String, value: String?) {
            if (origin.isBlank()) return
            val editor = prefs.edit()
            if (value == null) editor.remove(key(origin)) else editor.putString(key(origin), value)
            editor.apply()
        }

        override fun clear() = prefs.edit().clear().apply()
    }


    private data class Pending(
        val view: WebView, val origin: String, val androidPermissions: Array<String>,
        val media: PermissionRequest? = null, var rememberLocation: Boolean = false, val complete: (Boolean) -> Unit
    )
    private var pending: Pending? = null
    private var dialog: AlertDialog? = null
    private var awaitingAndroid = false

    fun requestMedia(view: WebView, request: PermissionRequest) {
        val resources = request.resources.filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }.toTypedArray()
        if (resources.isEmpty()) { request.deny(); return }
        val permissions = resources.map {
            if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA
        }.toTypedArray()
        ask(Pending(view, request.origin.toString(), permissions, request) { approved ->
            if (approved) request.grant(resources) else request.deny()
        }, resources.joinToString(" and ") { if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE) "microphone" else "camera" })
    }

    fun requestLocation(view: WebView, origin: String?, callback: GeolocationPermissions.Callback?) {
        if (callback == null) return
        ask(Pending(view, origin.orEmpty(), arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
            callback.invoke(origin, it, false)
        }, "location")
    }

    private fun valid(p: Pending): Boolean = !activity.isFinishing && !activity.isDestroyed && isActive(p.view) &&
        secureOrigin(p.origin) != null && secureOrigin(p.origin) == secureOrigin(p.view.url)

    private fun ask(p: Pending, capability: String) {
        // A result from an earlier OS permission dialog must never approve a new request.
        if (awaitingAndroid) { p.complete(false); return }
        cancel()
        if (!valid(p) || !activity.hasWindowFocus()) { p.complete(false); return }
        pending = p
        val isLocation = capability == "location" && locationStore != null
        val origin = secureOrigin(p.origin).orEmpty()
        fun allow(remember: Boolean = false) {
            if (pending !== p || !valid(p)) { finish(false); return }
            p.rememberLocation = remember
            if (hasPermissions(p)) finish(true) else {
                awaitingAndroid = true
                ActivityCompat.requestPermissions(activity, p.androidPermissions, REQUEST_CODE)
            }
        }
        if (isLocation) {
            when (decision(locationStore?.read(origin))) {
                true -> { allow(); return }
                false -> { finish(false); return }
                null -> Unit
            }
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle("Allow website access?")
            .setMessage("${secureOrigin(p.origin)}\n\nUse your $capability on this page? This does not approve other websites.")
            .setNegativeButton("Block") { _, _ ->
                if (isLocation && pending === p && valid(p)) locationStore?.write(origin, BLOCK)
                finish(false)
            }
            .setPositiveButton("Allow once") { _, _ -> allow() }
            .setOnCancelListener { finish(false) }
        if (isLocation) builder.setNeutralButton("Always on this site") { _, _ -> allow(true) }
        dialog = builder.show()
    }

    private fun hasPermissions(p: Pending): Boolean {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        return if (Manifest.permission.ACCESS_COARSE_LOCATION in p.androidPermissions)
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
        else p.androidPermissions.all(::granted)
    }

    fun onResult(requestCode: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false
        awaitingAndroid = false
        val p = pending
        finish(p != null && valid(p) && hasPermissions(p))
        return true
    }

    fun cancel(view: WebView? = null) {
        if (view == null || pending?.view === view) finish(false)
    }

    fun cancelled(request: PermissionRequest) {
        if (pending?.media === request) {
            // WebView has already cancelled this request. Do not grant/deny it again.
            pending = null
            dialog?.dismiss(); dialog = null
        }
    }

    private fun finish(approved: Boolean) {
        val p = pending
        pending = null
        dialog?.dismiss(); dialog = null
        if (p != null) {
            val granted = approved && valid(p)
            if (granted) {
                if (p.rememberLocation) locationStore?.write(secureOrigin(p.origin).orEmpty(), ALLOW)
                onAccessGranted(p.view)
            }
            p.complete(granted)
        }
    }

    companion object {
        const val ASK = "ask"
        const val ALLOW = "allow"
        const val BLOCK = "block"
        /** WebView supplies origins such as `https://example.com`; normalise to scheme + authority. */
        fun normalizeOrigin(raw: String?): String {
            val value = raw?.trim().orEmpty()
            if (value.isBlank()) return ""
            val scheme = if (value.contains("://"))
                value.substringBefore("://").lowercase(Locale.ROOT).ifBlank { "https" }
            else "https"
            val withoutScheme = if (value.contains("://")) value.substringAfter("://") else value
            val authority = withoutScheme
                .substringBefore('/').substringBefore('?').substringBefore('#')
                .trimEnd('/').lowercase(Locale.ROOT)
            return if (authority.isBlank()) value.trimEnd('/').lowercase(Locale.ROOT) else "$scheme://$authority"
        }

        /** null = ask the user, true = allow, false = block. */
        fun decision(stored: String?): Boolean? = when (stored?.trim()?.lowercase(Locale.ROOT)) {
            ALLOW -> true
            BLOCK -> false
            else -> null
        }

        /**
         * Choice index from the site permission dialog.
         * Returns the value to persist (empty = do not persist) and the WebView callback
         * arguments (allow, retain). Retain is always false: this store is the single
         * source of truth, so "Reset site permissions" can actually revoke a decision
         * instead of leaving a hidden grant in WebView's own database.
         */
        fun grantFor(choice: Int): Pair<String, Pair<Boolean, Boolean>> = when (choice) {
            0 -> "" to (true to false)          // Allow once
            1 -> ALLOW to (true to false)       // Always allow on this site (persisted here)
            else -> BLOCK to (false to false)   // Block
        }

        fun forget(origin: String, store: Store) = store.write(origin, null)

        const val REQUEST_CODE = 7041
        internal fun secureOrigin(value: String?): String? = runCatching {
            val uri = URI(value ?: return null)
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null) return null
            "https://${uri.host.lowercase(java.util.Locale.ROOT)}" + if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
        }.getOrNull()
    }
}
