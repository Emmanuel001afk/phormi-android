package com.uong.phormi

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

/** Lightweight news surface. Likes/dislikes locally re-rank future stories without a server profile. */
class NewsActivity : AppCompatActivity() {
    data class Story(val title: String, val url: String, val source: String, val category: String)
    private val stories = mutableListOf<Story>()
    private lateinit var adapter: BaseAdapter
    private val prefs by lazy { getSharedPreferences("phormi_news", MODE_PRIVATE) }

    private val feeds = listOf(
        Triple("https://feeds.bbci.co.uk/news/rss.xml", "BBC News", "general"),
        Triple("https://feeds.bbci.co.uk/news/technology/rss.xml", "BBC Technology", "technology"),
        Triple("https://feeds.bbci.co.uk/news/business/rss.xml", "BBC Business", "business")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)
        findViewById<TextView>(R.id.news_back).setOnClickListener { finish() }
        adapter = object : BaseAdapter() {
            override fun getCount() = stories.size
            override fun getItem(position: Int) = stories[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = convertView ?: layoutInflater.inflate(R.layout/item_news, parent, false)
                val s = stories[position]
                v.findViewById<TextView>(R.id.news_title).text = s.title
                v.findViewById<TextView>(R.id.news_meta).text = "${s.source} · ${s.category}"
                v.setOnClickListener { startActivity(android.content.Intent(this@NewsActivity, MainActivity::class.java).setData(android.net.Uri.parse(s.url))) }
                v.setOnLongClickListener {
                    val key = "cat_${s.category}"
                    prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
                    Toast.makeText(this@NewsActivity, "More like this", Toast.LENGTH_SHORT).show()
                    true
                }
                return v
            }
        }
        findViewById<ListView>(R.id.news_list).adapter = adapter
        load()
    }

    private fun load() {
        Thread {
            val out = mutableListOf<Story>()
            feeds.forEach { (url, source, category) -> out += fetch(url, source, category) }
            val ranked = out.sortedByDescending { prefs.getInt("cat_${it.category}", 0) }.take(40)
            runOnUiThread { stories.clear(); stories.addAll(ranked); adapter.notifyDataSetChanged() }
        }.start()
    }

    private fun fetch(feedUrl: String, source: String, category: String): List<Story> {
        return try {
            val connection = URL(feedUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "Phormi/1.0")
            val parser = android.util.Xml.newPullParser()
            val out = mutableListOf<Story>()
            connection.inputStream.use { stream ->
                parser.setInput(stream, "UTF-8")
                var event = parser.eventType
                var title = ""
                var link = ""
                var inItem = false
                while (event != XmlPullParser.END_DOCUMENT && out.size < 20) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            if (parser.name.equals("item", true) || parser.name.equals("entry", true)) inItem = true
                            else if (inItem && parser.name.equals("title", true)) title = parser.nextText().trim()
                            else if (inItem && parser.name.equals("link", true)) {
                                val href = parser.getAttributeValue(null, "href")
                                link = href?.takeIf { it.isNotBlank() } ?: parser.nextText().trim()
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name.equals("item", true) || parser.name.equals("entry", true)) {
                                if (title.isNotBlank() && link.startsWith("http")) out += Story(title, link, source, category)
                                title = ""; link = ""; inItem = false
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            connection.disconnect()
            out
        } catch (_: Exception) { emptyList() }
    }
}
