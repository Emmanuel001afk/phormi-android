package com.uong.phormi

import android.content.Context
import android.content.ComponentCallbacks2
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TabMemoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() {
        context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun restoringTwentyTabsAllocatesOnlyTheActiveWebView() {
        context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE).edit()
            .putString("tab_urls", JSONArray((1..20).map { "about:blank" }).toString())
            .putString("tab_ids", JSONArray((1..20).toList()).toString()).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertEquals(20, it.tabMemoryDiagnostics().getInt("total"))
                assertEquals(1, it.tabMemoryDiagnostics().getInt("live"))
            }
        }
    }
    @Test fun memoryPressureDestroysInactiveViewsAndSwitchingRecreatesThem() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("tabs").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val tabs = field.get(activity) as List<MainActivity.Tab>
                repeat(7) { activity.handleCentralHubCommand(JSONObject().put("command", "new_tab")) }
                val first = tabs.first()
                val original = first.webView
                assertNotNull(original)
                activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                assertEquals(1, activity.tabMemoryDiagnostics().getInt("live"))
                assertNull(first.webView)
                assertNull(original!!.parent)
                first.chipView.performClick()
                assertNotNull(first.webView)
                assertNotSame(original, first.webView)
            }
        }
    }
    @Test fun rendererSafetyProbeProtectsEditsFramesAndMedia() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: WebView
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("tabs").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                view = (field.get(activity) as List<MainActivity.Tab>).first().webView!!
                view.loadDataWithBaseURL(null, "<html><body><input id='draft'></body></html>", "text/html", "UTF-8", null)
            }
            fun js(script: String): String {
                val done = CountDownLatch(1)
                var answer = ""
                scenario.onActivity { view.evaluateJavascript(script) { answer = it; done.countDown() } }
                assertTrue("JavaScript completed", done.await(10, TimeUnit.SECONDS))
                return answer
            }
            // Wait for page load, not a fixed device-speed-dependent delay.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (js("document.readyState") != "\"complete\"" && System.nanoTime() < deadline) Thread.sleep(50)
            assertEquals("true", js(PhormiTabMemoryPolicy.CAN_DISCARD_SCRIPT))
            js("document.getElementById('draft').value='unsaved'")
            assertEquals("false", js(PhormiTabMemoryPolicy.CAN_DISCARD_SCRIPT))
            js("document.getElementById('draft').value=''; document.body.appendChild(document.createElement('iframe'))")
            assertEquals("false", js(PhormiTabMemoryPolicy.CAN_DISCARD_SCRIPT))
            js("document.querySelector('iframe').remove(); var a=document.createElement('audio'); Object.defineProperty(a,'paused',{value:false}); document.body.appendChild(a)")
            assertEquals("false", js(PhormiTabMemoryPolicy.CAN_DISCARD_SCRIPT))
            js("document.querySelector('audio').remove()")
            js(PhormiTabMemoryPolicy.INSTALL_DIRTY_TRACKER)
            js("document.getElementById('draft').dispatchEvent(new Event('input',{bubbles:true}))")
            assertEquals("false", js(PhormiTabMemoryPolicy.CAN_DISCARD_SCRIPT))
        }
    }
}
