package com.uong.phormi

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import de.blinkt.openvpn.api.IOpenVPNAPIService

/**
 * Controls the open-source OpenVPN for Android engine through its documented
 * remote API. Phormi does not create a fake TUN or drop packets itself.
 */
class OpenVpnController(private val context: Context) {
    companion object {
        const val OPENVPN_PACKAGE = "de.blinkt.openvpn"
        const val API_ACTION = "de.blinkt.openvpn.api.IOpenVPNAPIService"
    }

    private var api: IOpenVPNAPIService? = null
    private var connection: ServiceConnection? = null
    private var pendingConfig: String? = null
    private var pendingActivity: Activity? = null

    fun connect(activity: Activity, config: String, onResult: (Boolean, String) -> Unit) {
        pendingConfig = config
        pendingActivity = activity
        if (!isInstalled()) {
            onResult(false, "OpenVPN for Android is not installed")
            return
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                api = IOpenVPNAPIService.Stub.asInterface(binder)
                val service = api ?: run {
                    onResult(false, "OpenVPN API unavailable")
                    return
                }
                try {
                    val permissionIntent = service.prepare(context.packageName)
                    if (permissionIntent != null) {
                        pendingActivity?.startActivityForResult(permissionIntent, 9321)
                        onResult(true, "OpenVPN permission requested")
                        return
                    }
                    val vpnPermission = service.prepareVPNService()
                    if (vpnPermission != null) {
                        pendingActivity?.startActivityForResult(vpnPermission, 9322)
                        onResult(true, "OpenVPN VPN permission requested")
                        return
                    }
                    service.startVPN(config)
                    onResult(true, "OpenVPN tunnel start requested")
                } catch (e: Exception) {
                    onResult(false, "OpenVPN start failed: ${e.message ?: "unknown error"}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                api = null
            }
        }
        connection = conn
        val intent = Intent(API_ACTION).setPackage(OPENVPN_PACKAGE)
        val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!bound) {
            connection = null
            onResult(false, "Could not connect to OpenVPN API")
        }
    }

    fun resumeAfterPermission(onResult: (Boolean, String) -> Unit) {
        val service = api ?: run {
            onResult(false, "OpenVPN API connection is no longer available")
            return
        }
        val config = pendingConfig ?: run {
            onResult(false, "No pending OpenVPN profile")
            return
        }
        try {
            service.startVPN(config)
            onResult(true, "OpenVPN tunnel start requested")
        } catch (e: Exception) {
            onResult(false, "OpenVPN start failed: ${e.message ?: "unknown error"}")
        }
    }

    fun disconnect() {
        try { api?.disconnect() } catch (_: Exception) {}
        unbind()
    }

    fun unbind() {
        connection?.let {
            try { context.unbindService(it) } catch (_: Exception) {}
        }
        connection = null
        api = null
    }

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(OPENVPN_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }
}
