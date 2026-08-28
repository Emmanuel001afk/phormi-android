package com.uong.phormi

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Free public servers from VPN Gate (https://www.vpngate.net).
 * Lists country + score. Saves OpenVPN profile data for tunnel core next.
 */
class VpnActivity : AppCompatActivity() {

    data class VpnServer(
        val country: String,
        val countryCode: String,
        val ip: String,
        val score: Long,
        val ping: Int,
        val speed: Long,
        val ovpnBase64: String
    )

    private lateinit var listView: ListView
    private lateinit var status: TextView
    private lateinit var loading: TextView
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn)

        listView = findViewById(R.id.vpn_list)
        status = findViewById(R.id.vpn_status)
        loading = findViewById(R.id.vpn_loading)

        findViewById<TextView>(R.id.vpn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.vpn_refresh).setOnClickListener { loadServers() }

        val prefs = getSharedPreferences("phormi_vpn", MODE_PRIVATE)
        val selected = prefs.getString("selected_label", null)
        if (selected != null) {
            status.text = "Selected: $selected (tunnel core next build)"
        }

        loadServers()
    }

    private fun loadServers() {
        loading.visibility = View.VISIBLE
        listView.visibility = View.GONE
        executor.execute {
            val servers = fetchVpnGate()
            runOnUiThread {
                loading.visibility = View.GONE
                listView.visibility = View.VISIBLE
                if (servers.isEmpty()) {
                    status.text = "Could not load servers — try Refresh"
                    Toast.makeText(this, "VPN Gate list empty or offline", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val top = servers.sortedByDescending { it.score }.take(30)
                status.text = "${top.size} free public servers (VPN Gate)"
                listView.adapter = object : ArrayAdapter<VpnServer>(
                    this, android.R.layout.simple_list_item_2, android.R.id.text1, top
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val v = super.getView(position, convertView, parent)
                        val s = top[position]
                        val t1 = v.findViewById<TextView>(android.R.id.text1)
                        val t2 = v.findViewById<TextView>(android.R.id.text2)
                        t1.setTextColor(0xFFF1F5F9.toInt())
                        t2.setTextColor(0xFF94A3B8.toInt())
                       t1.text = "${s.country} (${s.countryCode}) — ${s.ip}"
                        t2.text = "Score ${s.score} · Ping ${s.ping} ms"
                        return v
                    }
                }
                listView.setOnItemClickListener { _, _, position, _ ->
                    val s = top[position]
                    getSharedPreferences("phormi_vpn", MODE_PRIVATE).edit()
                        .putString("selected_label", "${s.country} ${s.ip}")
                        .putString("selected_ovpn", s.ovpnBase64)
                        .putString("selected_ip", s.ip)
                        .apply()
                    status.text = "Selected: ${s.country} ${s.ip}\nOpenVPN tunnel connects in next core build"
                    Toast.makeText(this, "Server saved: ${s.country}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchVpnGate(): List<VpnServer> {
        return try {
            val conn = URL("http://www.vpngate.net/api/iphone/").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "PhormiApp/1.0")
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseVpnGateCsv(text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseVpnGateCsv(csv: String): List<VpnServer> {
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
            list.add(VpnServer(country, code, ip, score, ping, speed, ovpn))
        }
        return list
    }
}
