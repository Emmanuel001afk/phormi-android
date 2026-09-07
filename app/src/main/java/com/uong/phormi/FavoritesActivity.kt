package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Dedicated favorites screen. Favorites are separate from the bookmark list and never render on the home page. */
class FavoritesActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16); setBackgroundColor(0xFF0B1220.toInt()) }
        val title = TextView(this).apply { text = "Favorites"; textSize = 22f; setTextColor(0xFFF8FAFC.toInt()); setPadding(0,0,0,12) }
        val back = TextView(this).apply { text = "‹  Back"; textSize = 15f; setTextColor(0xFF38BDF8.toInt()); setPadding(0,0,0,12); setOnClickListener { finish() } }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(title); root.addView(back); root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        render()
    }

    override fun onResume() { super.onResume(); if (::list.isInitialized) render() }

    private fun render() {
        list.removeAllViews()
        val favorites = PhormiFavorites.getAll(this)
        if (favorites.isEmpty()) {
            list.addView(TextView(this).apply { text = "No favorites yet. Open a page and tap the decagon favorite icon."; setTextColor(0xFF94A3B8.toInt()); gravity = Gravity.CENTER; setPadding(24,40,24,40) })
            return
        }
        favorites.forEach { favorite ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14,12,14,12); setBackgroundColor(0xFF172033.toInt()) }
            row.addView(TextView(this).apply { text = favorite.title; textSize = 16f; setTextColor(0xFFF8FAFC.toInt()) })
            row.addView(TextView(this).apply { text = favorite.url; textSize = 11f; setTextColor(0xFF94A3B8.toInt()); setPadding(0,4,0,0) })
            row.setOnClickListener { setResult(RESULT_OK, Intent().putExtra("open_url", favorite.url)); finish() }
            row.setOnLongClickListener { confirmRemove(favorite.url, favorite.title); true }
            list.addView(row, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = 8 })
        }
    }

    private fun confirmRemove(url: String, title: String) {
        AlertDialog.Builder(this).setTitle("Remove favorite?").setMessage(title)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> PhormiFavorites.remove(this, url); render() }.show()
    }
}
