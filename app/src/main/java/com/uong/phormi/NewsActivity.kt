package com.uong.phormi

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight news surface.
 *
 * Likes are stored locally and used to re-rank future stories.
 * Story rows are created programmatically so the activity does not depend on
 * a missing item_news.xml resource.
 */
class NewsActivity : AppCompatActivity() {

    data class Story(
        val title: String,
        val url: String,
        val source: String,
        val category: String
    )

    private val stories = mutableListOf<Story>()
    private lateinit var adapter: BaseAdapter
    private val prefs by lazy {
        getSharedPreferences("phormi_news", MODE_PRIVATE)
    }

    private val feeds = listOf(
        Triple(
            "https://feeds.bbci.co.uk/news/rss.xml",
            "BBC News",
            "general"
        ),
        Triple(
            "https://feeds.bbci.co.uk/news/world/rss.xml",
            "BBC World",
            "world"
        ),
        Triple(
            "https://feeds.bbci.co.uk/news/africa/rss.xml",
            "BBC Africa",
            "africa"
        ),
        Triple(
            "https://feeds.bbci.co.uk/news/technology/rss.xml",
            "BBC Technology",
            "technology"
        ),
        Triple(
            "https://feeds.bbci.co.uk/news/business/rss.xml",
            "BBC Business",
            "business"
        ),
        Triple(
            "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml",
            "BBC Arts",
            "culture"
        ),
        Triple(
            "https://feeds.bbci.co.uk/sport/rss.xml",
            "BBC Sport",
            "sport"
        )
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)

        findViewById<TextView>(R.id.news_back)
            .setOnClickListener { finish() }

        adapter = object : BaseAdapter() {
            override fun getCount() = stories.size
            override fun getItem(position: Int) = stories[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup?
            ): View {
                val s = stories[position]

                val v = convertView ?: LinearLayout(this@NewsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        dp(18),
                        dp(12),
                        dp(18),
                        dp(12)
                    )
                    minimumHeight = dp(76)
                    setBackgroundColor(Color.TRANSPARENT)

                    addView(
                        TextView(this@NewsActivity).apply {
                            id = View.generateViewId()
                            tag = "news_title"
                            setTextColor(Color.rgb(248, 250, 252))
                            textSize = 15f
                            maxLines = 2
                            ellipsize =
                                android.text.TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )

                    addView(
                        TextView(this@NewsActivity).apply {
                            id = View.generateViewId()
                            tag = "news_meta"
                            setTextColor(Color.rgb(148, 163, 184))
                            textSize = 11f
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(5)
                        }
                    )
                }

                val title = v.findViewWithTag<TextView>("news_title")
                val meta = v.findViewWithTag<TextView>("news_meta")

                title.text = s.title
                meta.text = "${s.source} · ${s.category}"

                v.setOnClickListener {
                    startActivity(
                        android.content.Intent(
                            this@NewsActivity,
                            MainActivity::class.java
                        ).setData(android.net.Uri.parse(s.url))
                    )
                }

                v.setOnLongClickListener {
                    val key = "cat_${s.category}"
                    prefs.edit()
                        .putInt(key, prefs.getInt(key, 0) + 1)
                        .apply()

                    Toast.makeText(
                        this@NewsActivity,
                        "More like this",
                        Toast.LENGTH_SHORT
                    ).show()
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

            feeds.forEach { (url, source, category) ->
                out += fetch(url, source, category)
            }

            val ranked = out
                .distinctBy { canonical(it.url) }
                .sortedWith(
                    compareByDescending<Story> {
                        prefs.getInt("cat_${it.category}", 0)
                    }.thenByDescending {
                        when (it.category) {
                            "local" -> 5
                            "world", "africa" -> 4
                            "technology", "business" -> 3
                            else -> 1
                        }
                    }
                )
                .take(60)

            runOnUiThread {
                stories.clear()
                stories.addAll(ranked)
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun fetch(
        feedUrl: String,
        source: String,
        category: String
    ): List<Story> {
        return try {
            val connection =
                URL(feedUrl).openConnection() as HttpURLConnection

            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.setRequestProperty(
                "User-Agent",
                "Phormi/1.0"
            )

            val parser = android.util.Xml.newPullParser()
            val out = mutableListOf<Story>()

            connection.inputStream.use { stream ->
                parser.setInput(stream, "UTF-8")
                var event = parser.eventType
                var title = ""
                var link = ""
                var inItem = false

                while (
                    event != XmlPullParser.END_DOCUMENT &&
                    out.size < 20
                ) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            if (
                                parser.name.equals(
                                    "item",
                                    true
                                ) ||
                                parser.name.equals(
                                    "entry",
                                    true
                                )
                            ) {
                                inItem = true
                            } else if (
                                inItem &&
                                parser.name.equals(
                                    "title",
                                    true
                                )
                            ) {
                                title = parser.nextText().trim()
                            } else if (
                                inItem &&
                                parser.name.equals(
                                    "link",
                                    true
                                )
                            ) {
                                val href =
                                    parser.getAttributeValue(
                                        null,
                                        "href"
                                    )
                                link =
                                    href?.takeIf {
                                        it.isNotBlank()
                                    } ?: parser.nextText().trim()
                            }
                        }

                        XmlPullParser.END_TAG -> {
                            if (
                                parser.name.equals(
                                    "item",
                                    true
                                ) ||
                                parser.name.equals(
                                    "entry",
                                    true
                                )
                            ) {
                                if (
                                    title.isNotBlank() &&
                                    link.startsWith("http")
                                ) {
                                    out += Story(
                                        title,
                                        link,
                                        source,
                                        category
                                    )
                                }
                                title = ""
                                link = ""
                                inItem = false
                            }
                        }
                    }
                    event = parser.next()
                }
            }

            connection.disconnect()
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun canonical(url: String): String {
        return try {
            java.net.URL(url).run {
                "${host.lowercase().removePrefix("www.")}" +
                    "${path.ifBlank { "/" }}".lowercase()
            }
        } catch (_: Exception) {
            url.lowercase().substringBefore("#")
        }
    }
}
