package com.uong.phormi

import android.app.Activity
import android.content.Intent
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Multi-source free VPN list + connect flow.
 *
 * Connect:
 *  1) Saves the selected OpenVPN profile
 *  2) Sends the inline profile to the open-source OpenVPN for Android engine
 *  3) Lets that engine own the Android VPN tunnel
 *
 * Phormi deliberately does NOT establish a second local TUN or drain/drop
 * packets. That was the bug that blocked the whole phone when VPN was enabled.
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

    private val openVpnController by lazy { OpenVpnController(this) }

    private fun refreshConnectionUi() {
        val running = getSharedPreferences("phormi_vpn", MODE_PRIVATE)
            .getBoolean("vpn_requested", false)
        btnDisconnect.isEnabled = running
        btnConnect.isEnabled = !running
    }

    private fun connectSelected() {
        val prefs = getSharedPreferences("phormi_vpn", MODE_PRIVATE)
        val ovpnB64 = prefs.getString("selected_ovpn", null)
        val label = prefs.getString("selected_label", null)
        if (ovpnB64.isNullOrBlank() || label.isNullOrBlank()) {
            Toast.makeText(this, "Select a server first", Toast.LENGTH_SHORT).show()
            return
        }

        val config = decodeOvpn(ovpnB64)
        if (config.isNullOrBlank()) {
            Toast.makeText(this, "The selected VPN profile is invalid", Toast.LENGTH_LONG).show()
            return
        }

        status.text = "Connecting · $label"
        openVpnController.connect(this, config) { ok, message ->
            runOnUiThread {
                if (ok) {
                    prefs.edit().putBoolean("vpn_requested", true).apply()
                    status.text = "VPN requested · $label"
                } else {
                    prefs.edit().putBoolean("vpn_requested", false).apply()
                    status.text = "VPN unavailable · $message"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                refreshConnectionUi()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9321 || requestCode == 9322) {
            if (resultCode == Activity.RESULT_OK) {
                openVpnController.resumeAfterPermission { ok, message ->
                    runOnUiThread {
                        val prefs = getSharedPreferences("phormi_vpn", MODE_PRIVATE)
                        prefs.edit().putBoolean("vpn_requested", ok).apply()
                        status.text = message
                        if (!ok) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        refreshConnectionUi()
                    }
                }
            } else {
                getSharedPreferences("phormi_vpn", MODE_PRIVATE).edit()
                    .putBoolean("vpn_requested", false).apply()
                status.text = "VPN permission cancelled"
                refreshConnectionUi()
            }
        }
    }

    private fun disconnectVpn() {
        openVpnController.disconnect()
        getSharedPreferences("phormi_vpn", MODE_PRIVATE).edit()
            .putBoolean("vpn_requested", false).apply()
        status.text = "Disconnected"
        refreshConnectionUi()
    }

    private fun decodeOvpn(ovpnB64: String): String? {
        return try {
            val raw = ovpnB64.trim()
            val decoded = try {
                Base64.decode(raw, Base64.DEFAULT)
            } catch (_: Exception) {
                Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP)
            }
            String(decoded, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        openVpnController.unbind()
        executor.shutdownNow()
        super.onDestroy()
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
