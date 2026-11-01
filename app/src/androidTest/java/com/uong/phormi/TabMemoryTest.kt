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
    private fun value(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun live(tab: Any) = value(tab, "webView") as? WebView
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
                val tabs = field.get(activity) as List<Any>
                repeat(7) { activity.handleCentralHubCommand(JSONObject().put("command", "new_tab")) }
                val first = tabs.first()
                val original = live(first)
                assertNotNull(original)
                activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                assertEquals(1, activity.tabMemoryDiagnostics().getInt("live"))
                assertNull(live(first))
                assertNull(original!!.parent)
                (value(first, "chipView") as android.view.View).performClick()
                assertNotNull(live(first))
                assertNotSame(original, live(first))
            }
        }
    }
    @Test fun suspendedNavigationRestoresHistoryAndDirtyPageStaysLive() {
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val request = input.readLine().orEmpty()
                        while (!input.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                        val page = if (request.contains("/two")) "Two" else "One"
                        val body = "<html><head><title>$page</title></head><body><input id='draft' value='saved'><div style='height:2500px'>$page</div></body></html>".toByteArray()
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()
                        socket.getOutputStream().apply { write(headers); write(body); flush() }
                    }
                } catch (_: java.io.IOException) { if (server.isClosed) break }
            }
        }.apply { isDaemon = true; start() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var tab: Any
                fun waitUntil(message: String, test: () -> Boolean) {
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    while (System.nanoTime() < end) { if (test()) return; Thread.sleep(50) }
                    fail(message)
                }
                fun pageIs(title: String): Boolean {
                    var ready = false
                    scenario.onActivity { ready = live(tab)?.let { it.title == title && it.progress == 100 } == true }
                    return ready
                }
                val base = "http://127.0.0.1:${server.localPort}"
                scenario.onActivity { activity ->
                    activity.handleCentralHubCommand(JSONObject().put("command", "open_url").put("url", "$base/one"))
                    tab = (value(activity, "tabs") as List<*>).last()!!
                }
                waitUntil("First fixture page loaded") { pageIs("One") }
                scenario.onActivity { live(tab)!!.loadUrl("$base/two") }
                waitUntil("Second fixture page loaded") { pageIs("Two") }
                scenario.onActivity { activity ->
                    assertTrue(live(tab)!!.canGoBack())
                    repeat(2) { activity.handleCentralHubCommand(JSONObject().put("command", "new_tab")) }
                    activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                }
                waitUntil("Background document was genuinely suspended") {
                    var suspended = false; scenario.onActivity { suspended = live(tab) == null }; suspended
                }
                scenario.onActivity { (value(tab, "chipView") as android.view.View).performClick() }
                waitUntil("Suspended document restored") { pageIs("Two") }
                scenario.onActivity { assertTrue(live(tab)!!.canGoBack()); live(tab)!!.goBack() }
                waitUntil("Restored Back navigation reaches the first page") { pageIs("One") }
                val edited = CountDownLatch(1)
                scenario.onActivity { live(tab)!!.evaluateJavascript("document.getElementById('draft').value='keep this draft'; true") { edited.countDown() } }
                assertTrue(edited.await(10, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    activity.handleCentralHubCommand(JSONObject().put("command", "new_tab"))
                    activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                }
                waitUntil("Unsaved form is protected") {
                    var protected = false
                    scenario.onActivity { protected = live(tab) != null && value(tab, "discardReason").toString().startsWith("Protected:") }
                    protected
                }
            }
        } finally { server.close(); worker.join(2000) }
    }

    @Test fun rendererSafetyProbeProtectsEditsFramesAndMedia() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: WebView
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("tabs").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                view = live((field.get(activity) as List<Any>).first())!!
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
            while (js("document.readyState === 'complete' && !!document.getElementById('draft')") != "true" && System.nanoTime() < deadline) Thread.sleep(50)
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
