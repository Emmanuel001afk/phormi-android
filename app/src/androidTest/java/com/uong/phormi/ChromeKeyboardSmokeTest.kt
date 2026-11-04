package com.uong.phormi

import android.content.Context
import android.content.Intent
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
        prefs.edit().remove(PhormiBrowserPreferences.BOTTOM_TOOLBAR).commit()
        context.getSharedPreferences("phormi_keyboard", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun openSite(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val input = activity.findViewById<EditText>(R.id.url_bar)
            input.setText("example.com")
            input.onEditorAction(EditorInfo.IME_ACTION_GO)
        }
    }

    @Test fun topBarCarriesNavigationWhenBottomBarIsOff() {
        prefs.edit().putBoolean(PhormiBrowserPreferences.BOTTOM_TOOLBAR, false).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSite(scenario)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.top_toolbar).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.bottom_toolbar).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_home).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_top_back).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_top_forward).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.url_toolbar).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tab_count).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_menu).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_refresh).visibility)
                val density = activity.resources.displayMetrics.density
                assertTrue(activity.findViewById<View>(R.id.top_toolbar).layoutParams.height <= (48 * density).toInt())
            }
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "chrome-top-only.png"))
        }
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
        previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        device.executeShellCommand("ime enable com.uong.phormi/.PhormiKeyboardServiceV2")
        device.executeShellCommand("ime set com.uong.phormi/.PhormiKeyboardServiceV2")
    }

    private fun letterKeyHeight(): Int {
        assertTrue("Keyboard must be shown", device.wait(Until.hasObject(By.desc("Backspace")), 7000))
        device.waitForIdle()
        val key = device.wait(Until.findObject(By.desc(Pattern.compile("q", Pattern.CASE_INSENSITIVE))), 7000)
        assertNotNull("Letter q must be visible", key)
        key!!.refresh()
        return key.visibleBounds.height()
    }

    @Test fun keyboardLetterKeysAreReadableAndHeightPresetIsReal() {
        enableKeyboard()
        PhormiKeyboardPreferences.setHeightScale(context, 0.85f)
        val compact = ActivityScenario.launch<KeyboardTestActivity>(Intent(context, KeyboardTestActivity::class.java)
            .putExtra("input_type", InputType.TYPE_CLASS_TEXT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val height = letterKeyHeight()
            val density = context.resources.displayMetrics.density
            assertTrue("Compact keys must stay readable: height=$height px, density=$density", height >= (28 * density).toInt())
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "keyboard-compact.png"))
            height
        }
        PhormiKeyboardPreferences.setHeightScale(context, 1.20f)
        ActivityScenario.launch<KeyboardTestActivity>(Intent(context, KeyboardTestActivity::class.java)
            .putExtra("input_type", InputType.TYPE_CLASS_TEXT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val large = letterKeyHeight()
            assertTrue("Large preset must actually increase key height: compact=$compact large=$large", large > compact)
            assertTrue(device.hasObject(By.desc("Backspace")))
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "keyboard-large.png"))
        }
    }
}
