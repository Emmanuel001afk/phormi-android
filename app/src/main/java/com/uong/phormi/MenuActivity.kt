package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        findViewById<TextView>(R.id.menu_downloads).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        findViewById<TextView>(R.id.menu_tabs).setOnClickListener {
            startActivity(Intent(this, TabsOverviewActivity::class.java))
            finish()
        }
        findViewById<TextView>(R.id.menu_vpn).setOnClickListener {
            startActivity(Intent(this, VpnActivity::class.java))
        }
        findViewById<TextView>(R.id.menu_ai).setOnClickListener {
            startActivity(Intent(this, AiActivity::class.java))
        }
        findViewById<TextView>(R.id.menu_close).setOnClickListener { finish() }
    }
}
