package com.uong.phormi

import android.Manifest
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
 * Grants are deliberately one-shot: no hidden durable allowlist or private-mode persistence. */
class PhormiSitePermissions(private val activity: Activity, private val isActive: (WebView) -> Boolean, private val onAccessGranted: (WebView) -> Unit = {}) {
    private data class Pending(
        val view: WebView, val origin: String, val androidPermissions: Array<String>,
        val media: PermissionRequest? = null, val complete: (Boolean) -> Unit
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
        dialog = AlertDialog.Builder(activity)
            .setTitle("Allow website access?")
            .setMessage("${secureOrigin(p.origin)}\n\nUse your $capability on this page? This does not approve other websites.")
            .setNegativeButton("Block") { _, _ -> finish(false) }
            .setPositiveButton("Allow once") { _, _ ->
                if (pending !== p || !valid(p)) { finish(false); return@setPositiveButton }
                if (hasPermissions(p)) finish(true) else {
                    awaitingAndroid = true
                    ActivityCompat.requestPermissions(activity, p.androidPermissions, REQUEST_CODE)
                }
            }
            .setOnCancelListener { finish(false) }
            .show()
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
            if (granted) onAccessGranted(p.view)
            p.complete(granted)
        }
    }

    companion object {
        const val REQUEST_CODE = 7041
        internal fun secureOrigin(value: String?): String? = runCatching {
            val uri = URI(value ?: return null)
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null) return null
            "https://${uri.host.lowercase(java.util.Locale.ROOT)}" + if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
        }.getOrNull()
    }
}
