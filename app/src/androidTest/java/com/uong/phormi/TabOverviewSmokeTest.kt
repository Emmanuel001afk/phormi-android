package com.uong.phormi

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabOverviewSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val prefs get() = context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("phormi_tab_groups", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun seedTabs() {
        prefs.edit()
            .putString("tab_urls", JSONArray(listOf("https://github.com/", "https://example.com/", "about:blank", "https://news.example.org/")).toString())
            .putString("tab_titles", JSONArray(listOf("New Tab", "Example", "New tab", "News")).toString())
            .putString("tab_ids", JSONArray(listOf(11, 12, 13, 14)).toString())
            .putString("tab_profiles", JSONArray(listOf("Default", "Default", "Default", "Default")).toString())
            .putInt("active_index", 0).putString("tab_view_mode", "vertical").commit()
        val group = TabGroupManager(context).create("Work")
        TabGroupManager(context).assignTab(group.id, 14)
    }

    private fun tagged(root: View, tag: String): List<View> {
        val out = mutableListOf<View>()
        if (root.tag == tag) out += root
        if (root is ViewGroup) for (i in 0 until root.childCount) out += tagged(root.getChildAt(i), tag)
        return out
    }

    @Test fun overviewHasOrderedSectionsAndRepairsStaleTitle() {
        seedTabs()
        ActivityScenario.launch(TabsOverviewActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("Current tab")), 7000))
            assertTrue(device.hasObject(By.text("github.com")))
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.tabs_vertical)
                assertEquals(listOf("current", "group:", "websites", "new"),
                    (0 until root.childCount).mapNotNull { root.getChildAt(it).tag as? String }.filter { it == "current" || it.startsWith("group:") || it == "websites" || it == "new" })
                assertEquals(4, tagged(root, "tab-card").size)
                assertEquals("github.com", activity.findViewById<View>(R.id.tabs_vertical).let { firstCardTitle(it) })
                device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "tab-overview-list.png"))
            }
        }
    }

    private fun firstCardTitle(root: View): String {
        if (root.tag == "tab-card") return root.findViewById<TextView>(R.id.tab_card_title).text.toString()
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            val found = runCatching { firstCardTitle(root.getChildAt(i)) }.getOrNull()
            if (found != null) return found
        }
        error("No tab card")
    }

    @Test fun selectAllAppliesOnlyToSearchResults() {
        seedTabs()
        ActivityScenario.launchActivityForResult<TabsOverviewActivity>(Intent(context, TabsOverviewActivity::class.java)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.res("com.uong.phormi", "search_tabs")), 7000))
            device.findObject(By.res("com.uong.phormi", "search_tabs")).text = "example"
            assertTrue(device.wait(Until.hasObject(By.text("2 of 4 tabs match")), 3000))
            device.findObject(By.res("com.uong.phormi", "btn_select_tabs")).click()
            device.findObject(By.res("com.uong.phormi", "btn_select_all_tabs")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Close (2)")), 3000))
            device.findObject(By.res("com.uong.phormi", "btn_close_selected")).click()
            assertTrue(device.wait(Until.hasObject(By.res("android", "button1")), 3000))
            device.findObject(By.res("android", "button1")).click()
            val result = scenario.result
            assertArrayEquals(intArrayOf(12, 14), result.resultData?.getIntArrayExtra("tab_ids")?.sortedArray())
        }
    }

    @Test fun gridChoicePersistsAndCardsRemainReachable() {
        seedTabs()
        ActivityScenario.launch(TabsOverviewActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.desc("Grid layout")), 7000))
            device.findObject(By.desc("Grid layout")).click()
            assertEquals("grid", prefs.getString("tab_view_mode", null))
            assertTrue(device.hasObject(By.desc("Group News")) || device.hasObject(By.desc("Group github.com")))
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "tab-overview-grid.png"))
        }
    }

    @Test fun typedUrlSurvivesImmediateSnapshotAndHomeIsAlwaysReachable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.url_bar)
                input.setText("example.com")
                input.onEditorAction(EditorInfo.IME_ACTION_GO)
                val urls = JSONArray(prefs.getString("tab_urls", "[]"))
                val titles = JSONArray(prefs.getString("tab_titles", "[]"))
                assertEquals("https://example.com", urls.optString(urls.length() - 1))
                assertEquals("example.com", titles.optString(titles.length() - 1))
                val home = activity.findViewById<View>(R.id.btn_home)
                assertEquals(View.VISIBLE, home.visibility)
                val density = activity.resources.displayMetrics.density
                assertTrue(activity.findViewById<View>(R.id.top_toolbar).layoutParams.height <= (48 * density).toInt())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.bottom_toolbar).visibility)
                home.performClick()
                val afterHome = JSONArray(prefs.getString("tab_urls", "[]"))
                assertEquals("about:blank", afterHome.optString(afterHome.length() - 1))
            }
        }
    }
}
