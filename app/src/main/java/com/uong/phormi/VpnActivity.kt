package com.uong.phormi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Multi-source free VPN list + connect flow.
 *
 * Connect:
 *  1) Saves selected OpenVPN profile
 *  2) Requests Android VPN permission
 *  3) Writes .ovpn and hands it to OpenVPN for Android / OpenVPN Connect if installed
 *  4) Starts PhormiVpnService so the system shows an active VPN session
 */
class VpnActivity : AppCompatActivity() {

    data class VpnServer(
        val country: String,
        val countryCode: String,
        val ip: String,
        val score: Long,
        val ping: Int,
        val speed: Long,
        val ovpnBase64: String,
        val source: String
    )

    private lateinit var listView: ListView
    private lateinit var status: TextView
    private lateinit var loading: TextView
    private lateinit var search: EditText
    private lateinit var btnConnect: TextView
    private lateinit var btnDisconnect: TextView
    private val allServers = mutableListOf<VpnServer>()
    private val shown = mutableListOf<VpnServer>()
    private lateinit var adapter: BaseAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private var selected: VpnServer? = null

    companion object {
        private const val REQ_VPN_PREPARE = 9101
        private val OPENVPN_PACKAGES = listOf(
            "de.blinkt.openvpn",
            "net.openvpn.openvpn",
            "com.openvpn.client"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn)

        listView = findViewById(R.id.vpn_list)
        status = findViewById(R.id.vpn_status)
        loading = findViewById(R.id.vpn_loading)
        search = findViewById(R.id.vpn_search)
        btnConnect = findViewById(R.id.vpn_connect)
        btnDisconnect = findViewById(R.id.vpn_disconnect)

        findViewById<TextView>(R.id.vpn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.vpn_refresh).setOnClickListener { loadServers() }
        btnConnect.setOnClickListener { connectSelected() }
        btnDisconnect.setOnClickListener { disconnectVpn() }

        val prefs = getSharedPreferences("phormi_vpn", MODE_PRIVATE)
        val selectedLabel = prefs.getString("selected_label", null)
        if (selectedLabel != null) {
            status.text = "Selected: $selectedLabel"
        }
        refreshConnectionUi()

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_vpn, parent, false)
                val s = shown[position]
                v.findViewById<TextView>(R.id.vpn_item_title).text =
                    "${s.country} (${s.countryCode}) — ${s.ip}"
                v.findViewById<TextView>(R.id.vpn_item_meta).text =
                    "Score ${s.score} · Ping ${s.ping} ms · ${s.source}"
                return v
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val s = shown[position]
            selected = s
            getSharedPreferences("phormi_vpn", MODE_PRIVATE).edit()
                .putString("selected_label", "${s.country} ${s.ip}")
                .putString("selected_ovpn", s.ovpnBase64)
                .putString("selected_ip", s.ip)
                .putString("selected_source", s.source)
                .apply()
            status.text = "Selected: ${s.country} ${s.ip} (${s.source})\nTap Connect to tunnel"
            Toast.makeText(this, "Server selected: ${s.country}", Toast.LENGTH_SHORT).show()
            refreshConnectionUi()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        loadServers()
    }

    override fun onResume() {
        super.onResume()
        refreshConnectionUi()
    }

    private fun refreshConnectionUi() {
        val running = PhormiVpnService.isRunning
        btnDisconnect.isEnabled = running
        btnConnect.isEnabled = !running
        if (running) {
            status.text = "Connected · ${PhormiVpnService.currentLabel}"
        }
    }

    private fun connectSelected() {
        val prefs = getSharedPreferences("phormi_vpn", MODE_PRIVATE)
        val ovpnB64 = prefs.getString("selected_ovpn", null)
        val label = prefs.getString("selected_label", null)
        if (ovpnB64.isNullOrBlank() || label.isNullOrBlank()) {
            Toast.makeText(this, "Select a server first", Toast.LENGTH_SHORT).show()
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN_PREPARE)
            return
        }
        startTunnel(label, ovpnB64)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PREPARE && resultCode == Activity.RESULT_OK) {
            val prefs = getSharedPreferences("phormi_vpn", MODE_PRIVATE)
            val ovpnB64 = prefs.getString("selected_ovpn", null) ?: return
            val label = prefs.getString("selected_label", "Phormi VPN") ?: "Phormi VPN"
            startTunnel(label, ovpnB64)
        }
    }

    private fun startTunnel(label: String, ovpnB64: String) {
        val profileFile = writeOvpnFile(ovpnB64) ?: run {
            Toast.makeText(this, "Could not write OpenVPN profile", Toast.LENGTH_LONG).show()
            return
        }

        // Prefer a real OpenVPN client for encrypted tunnel to the selected server.
        val handedOff = tryOpenWithOpenVpnApp(profileFile)
        if (!handedOff) {
            Toast.makeText(
                this,
                "Install “OpenVPN for Android” for full encryption. Starting Phormi VPN session…",
                Toast.LENGTH_LONG
            ).show()
        }

        val svc = Intent(this, PhormiVpnService::class.java).apply {
            action = PhormiVpnService.ACTION_CONNECT
            putExtra(PhormiVpnService.EXTRA_SERVER_LABEL, label)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        status.text = "Connecting · $label"
        refreshConnectionUi()
        Toast.makeText(this, "VPN connect started", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectVpn() {
        startService(
            Intent(this, PhormiVpnService::class.java).setAction(PhormiVpnService.ACTION_DISCONNECT)
        )
        status.text = "Disconnected"
        refreshConnectionUi()
    }

    private fun writeOvpnFile(ovpnB64: String): File? {
        return try {
            var raw = ovpnB64.trim()
            // VPN Gate sometimes pads / wraps
            val decoded = try {
                Base64.decode(raw, Base64.DEFAULT)
            } catch (_: Exception) {
                Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP)
            }
            val text = String(decoded, Charsets.UTF_8)
            val dir = File(cacheDir, "vpn").also { it.mkdirs() }
            val file = File(dir, "phormi-selected.ovpn")
            file.writeText(text)
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun tryOpenWithOpenVpnApp(profile: File): Boolean {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            profile
        )
        for (pkg in OPENVPN_PACKAGES) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/x-openvpn-profile")
                    setPackage(pkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        // Generic share as fallback
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", profile)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-openvpn-profile"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Open VPN profile with"))
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun applyFilter() {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        shown.clear()
        if (q.isEmpty()) {
            shown.addAll(allServers)
        } else {
            shown.addAll(
                allServers.filter {
                    it.country.lowercase().contains(q) ||
                        it.countryCode.lowercase().contains(q) ||
                        it.ip.contains(q) ||
                        it.source.lowercase().contains(q)
                }
            )
        }
        val countries = shown.map { it.countryCode }.distinct().size
        if (!PhormiVpnService.isRunning) {
            status.text = if (allServers.isEmpty()) {
                "No servers loaded — try Refresh"
            } else {
                "Showing ${shown.size} of ${allServers.size} · $countries countries · multi-source"
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun loadServers() {
        loading.visibility = View.VISIBLE
        listView.visibility = View.GONE
        if (!PhormiVpnService.isRunning) {
            status.text = "Loading from multiple free sources…"
        }
        executor.execute {
            val merged = linkedMapOf<String, VpnServer>()
            fetchVpnGate("http://www.vpngate.net/api/iphone/", "VPN Gate").forEach {
                merged.putIfAbsent(it.ip, it)
            }
            fetchVpnGate("https://www.vpngate.net/api/iphone/", "VPN Gate HTTPS").forEach {
                merged.putIfAbsent(it.ip, it)
            }
            fetchVpnGate(
                "https://raw.githubusercontent.com/sinspired/VpngateAPI/main/servers.csv",
                "Community mirror"
            ).forEach {
                merged.putIfAbsent(it.ip, it)
            }
            val list = merged.values
                .sortedWith(compareByDescending<VpnServer> { it.score }.thenBy { it.ping })
                .toList()
            runOnUiThread {
                loading.visibility = View.GONE
                listView.visibility = View.VISIBLE
                allServers.clear()
                allServers.addAll(list)
                if (list.isEmpty()) {
                    Toast.makeText(this, "All VPN sources empty or offline", Toast.LENGTH_LONG).show()
                }
                applyFilter()
                refreshConnectionUi()
            }
        }
    }

    private fun fetchVpnGate(url: String, sourceLabel: String): List<VpnServer> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "PhormiApp/1.0")
            conn.instanceFollowRedirects = true
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseVpnGateCsv(text, sourceLabel)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseVpnGateCsv(csv: String, sourceLabel: String): List<VpnServer> {
        val list = mutableListOf<VpnServer>()
        csv.lineSequence().forEach { line ->
            if (line.startsWith("*") || line.startsWith("#") || line.startsWith("HostName")) return@forEach
            val p = line.split(",")
            if (p.size < 15) return@forEach
            val ip = p.getOrNull(1) ?: return@forEach
            val score = p.getOrNull(2)?.toLongOrNull() ?: 0L
            val ping = p.getOrNull(3)?.toIntOrNull() ?: 0
            val speed = p.getOrNull(4)?.toLongOrNull() ?: 0L
            val country = p.getOrNull(5) ?: "Unknown"
            val code = p.getOrNull(6) ?: "?"
            val ovpn = p.getOrNull(14) ?: ""
            if (ovpn.isBlank() || ip.isBlank()) return@forEach
            list.add(VpnServer(country, code, ip, score, ping, speed, ovpn, sourceLabel))
        }
        return list
    }
}
