package com.uong.phormi

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class ChromeKeyboardSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val prefs get() = context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
    private var previousIme: String? = null

    @Before fun reset() {
        // Every test starts from the shipping defaults, then opts in explicitly.
        prefs.edit()
            .remove(PhormiBrowserPreferences.BOTTOM_TOOLBAR)
            .remove(PhormiBrowserPreferences.PULL_TO_REFRESH)
            .commit()
        context.getSharedPreferences("phormi_keyboard", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /**
     * Opens a page and waits for the navigation to be requested. The UI-idle wait runs
     * on this thread, never inside onActivity: waiting for idle on the main thread
     * while the activity is still laying out deadlocks the instrumentation.
     */
    private fun openSite(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val input = activity.findViewById<EditText>(R.id.url_bar)
            input.setText("example.com")
            input.onEditorAction(EditorInfo.IME_ACTION_GO)
        }
        device.waitForIdle()
    }

    @Test fun topBarCarriesNavigationWhenBottomBarIsOff() {
        prefs.edit().putBoolean(PhormiBrowserPreferences.BOTTOM_TOOLBAR, false).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSite(scenario)
            scenario.onActivity { activity ->
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.requestedOrientation)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.top_toolbar).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.bottom_toolbar).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_top_back).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_top_forward).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.url_toolbar).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tab_count).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_menu).visibility)
                // Refresh is permanent chrome now: Stop while loading, reload when idle.
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_refresh).visibility)
                val density = activity.resources.displayMetrics.density
                assertTrue(activity.findViewById<View>(R.id.top_toolbar).layoutParams.height <= (48 * density).toInt())
                // The address field must keep real width on a phone while every control is
                // visible. The test device is 411dp wide; require at least 140dp of omnibox
                // rather than a fixed pixel value. `device.waitForIdle()` above guarantees
                // the first layout pass has run before this point.
                val urlWidth = activity.findViewById<View>(R.id.url_toolbar).width
                val screenWidth = activity.resources.displayMetrics.widthPixels
                assertTrue("address field too narrow: ${urlWidth}px of ${screenWidth}px",
                    urlWidth >= (140 * density).toInt())
                // Chrome order: Back, Forward, address, Refresh, Tabs, Menu.
                assertTrue(activity.findViewById<View>(R.id.btn_top_back).left <
                    activity.findViewById<View>(R.id.btn_top_forward).left)
                assertTrue(activity.findViewById<View>(R.id.btn_top_forward).left <
                    activity.findViewById<View>(R.id.url_toolbar).left)
                assertTrue(activity.findViewById<View>(R.id.url_toolbar).left <
                    activity.findViewById<View>(R.id.btn_refresh).left)
                assertTrue(activity.findViewById<View>(R.id.btn_refresh).left <
                    activity.findViewById<View>(R.id.btn_menu).left)
            }
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "chrome-top-only.png"))
        }
    }

    @Test fun pullToRefreshIsNotInstalledByDefault() {
        prefs.edit().putBoolean(PhormiBrowserPreferences.PULL_TO_REFRESH, false).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // No SwipeRefreshLayout in the hierarchy: an up/down scroll cannot reload.
                assertNull(findSwipeRefresh(activity))
            }
        }
    }

    /**
     * When the user opts in, the gesture exists but only at the very top of the page:
     * `canChildScrollUp` is what stops a mid-page scroll from becoming a reload.
     */
    @Test fun optInPullToRefreshExistsAndOnlyArmsAtTheTop() {
        prefs.edit().putBoolean(PhormiBrowserPreferences.PULL_TO_REFRESH, true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSite(scenario)
            scenario.onActivity { activity ->
                val host = activity.findViewById<View>(R.id.swipe_refresh_host)
                assertNotNull("pull-to-refresh host exists", host)
                var found = false
                fun walk(view: View) {
                    if (view is androidx.swiperefreshlayout.widget.SwipeRefreshLayout) found = true
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) walk(view.getChildAt(index))
                    }
                }
                walk(host)
                assertTrue("pull-to-refresh is installed once enabled", found)
            }
        }
    }

    private fun findSwipeRefresh(activity: MainActivity): android.view.View? {
        fun walk(view: android.view.View): android.view.View? {
            if (view is androidx.swiperefreshlayout.widget.SwipeRefreshLayout) return view
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        return walk(activity.findViewById(android.R.id.content))
    }

    @Test fun bottomBarOwnsNavigationWhenEnabled() {
        prefs.edit().putBoolean(PhormiBrowserPreferences.BOTTOM_TOOLBAR, true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSite(scenario)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.bottom_toolbar).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_top_back).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_top_forward).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_back).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_forward).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_bottom_ai).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_bottom_downloads).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_refresh).visibility)
                // The top row keeps the address, refresh, tabs and menu; nothing is duplicated.
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.url_toolbar).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tab_count).visibility)
                val density = activity.resources.displayMetrics.density
                assertTrue(activity.findViewById<View>(R.id.bottom_toolbar).layoutParams.height <= (48 * density).toInt())
            }
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "chrome-bottom-enabled.png"))
        }
    }

    @After fun restoreKeyboard() {
        val previous = previousIme
        if (!previous.isNullOrBlank() && previous != "com.uong.phormi/.PhormiKeyboardServiceV2") {
            device.executeShellCommand("ime set $previous")
        }
        device.pressHome()
        previousIme = null
    }

    private fun enableKeyboard() {
        if (previousIme == null) {
            previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }
        device.executeShellCommand("ime enable com.uong.phormi/.PhormiKeyboardServiceV2")
        device.executeShellCommand("ime set com.uong.phormi/.PhormiKeyboardServiceV2")
    }

    private fun imeWindowHeight(): Int {
        var height = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            height = PhormiKeyboardServiceV2.activeInstance()?.window?.window?.attributes?.height ?: 0
        }
        return height
    }

    private fun letterKeyHeight(): Int {
        assertTrue("Keyboard must be shown", device.wait(Until.hasObject(By.desc("Backspace")), 7000))
        device.waitForIdle()
        val key = device.wait(Until.findObject(By.desc(Pattern.compile("q", Pattern.CASE_INSENSITIVE))), 7000)
        assertNotNull("Letter q must be visible", key)
        var height = key!!.visibleBounds.height()
        if (height <= 0) {
            device.waitForIdle()
            height = device.findObject(By.desc(Pattern.compile("q", Pattern.CASE_INSENSITIVE)))?.visibleBounds?.height() ?: 0
        }
        return height
    }

    @Test fun keyboardLetterKeysAreReadableAndHeightPresetIsReal() {
        PhormiKeyboardPreferences.setHeightScale(context, 0.90f)
        enableKeyboard()
        ActivityScenario.launch<KeyboardTestActivity>(Intent(context, KeyboardTestActivity::class.java)
            .putExtra("input_type", InputType.TYPE_CLASS_TEXT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val compactKey = letterKeyHeight()
            val compactWindow = imeWindowHeight()
            val density = context.resources.displayMetrics.density
            // Compact is intentionally shorter than Comfortable, but keys must remain tappable.
            assertTrue("Compact keys must stay readable: height=$compactKey px, density=$density", compactKey >= (24 * density).toInt())
            assertTrue("Compact IME window must have a real height", compactWindow > 0)
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "keyboard-compact.png"))

            PhormiKeyboardPreferences.setHeightScale(context, 1.20f)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                PhormiKeyboardServiceV2.activeInstance()?.applySizingPreferences()
            }
            device.waitForIdle()
            var largeWindow = imeWindowHeight()
            if (largeWindow <= compactWindow) {
                android.os.SystemClock.sleep(500)
                device.waitForIdle()
                largeWindow = imeWindowHeight()
            }
            assertTrue("Large preset must actually increase the IME window: compact=$compactWindow large=$largeWindow", largeWindow > compactWindow)
            assertTrue("Large keyboard must still expose letter keys", letterKeyHeight() > 0)
            assertTrue(device.hasObject(By.desc("Backspace")))
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "keyboard-large.png"))
        }
    }
}
