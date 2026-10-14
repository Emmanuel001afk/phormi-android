package com.uong.phormi

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.SearchCondition
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android IME windows/editor connections, including the same-process browser. */
@RunWith(AndroidJUnit4::class)
class KeyboardIntegrationSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    @Before fun setup() {
        context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE).edit().putBoolean("news_enabled", false).commit()
        context.getSharedPreferences("phormi_keyboard", Context.MODE_PRIVATE).edit().clear().putBoolean("haptic", false).putBoolean("sound", false).commit()
        device.executeShellCommand("ime enable com.uong.phormi/.PhormiKeyboardServiceV2")
        device.executeShellCommand("ime set com.uong.phormi/.PhormiKeyboardServiceV2")
    }
    private fun openEditor(): ActivityScenario<KeyboardSmokeActivity> = ActivityScenario.launch<KeyboardSmokeActivity>(
        Intent(context, KeyboardSmokeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ).also { scenario -> scenario.onActivity { activity ->
        activity.editor.requestFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(activity.editor, InputMethodManager.SHOW_IMPLICIT)
    } }
    private fun tap(label: String) {
        val key = device.wait(Until.findObject(By.text(label).clazz("android.widget.Button")), 8_000)
        assertNotNull("Keyboard control not visible: $label", key)
        key!!.click(); device.waitForIdle()
    }
    private fun capture(name: String) {
        runCatching {
            val file = File(context.getExternalFilesDir(null), "$name.png")
            device.takeScreenshot(file)
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "$name.xml"))
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 432, (bitmap.height * 432f / bitmap.width).toInt(), true)
            File(context.getExternalFilesDir(null), "$name-preview.jpg").outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 45, it) }
            small.recycle(); bitmap.recycle()
        }
    }
    private fun expect(scenario: ActivityScenario<KeyboardSmokeActivity>, value: String) {
        var actual = ""
        device.wait(object : SearchCondition<Boolean>() {
            override fun apply(device: UiDevice): Boolean {
                scenario.onActivity { actual = it.editor.text.toString() }
                return actual == value
            }
        }, 3_000)
        if (actual != value) capture("phormi-typing-failure")
        assertEquals(value, actual)
    }
    @Test fun sameProcessTypingCorrectsAndUndoRetainsTheDeliberateWord() {
        openEditor().use { scenario ->
            try {
                tap("H"); tap("e"); tap("l"); tap("l"); tap("o"); tap("Space")
                expect(scenario, "Hello ")
                tap("t"); tap("e"); tap("h"); tap("Space")
                expect(scenario, "Hello the ")
                tap("⌫"); expect(scenario, "Hello teh")
                tap("Space"); expect(scenario, "Hello teh ")
                capture("phormi-typing-smoke")
            } finally { capture("phormi-typing-final") }
        }
    }
    @Test fun emojiWritingPhoneAndBrowserPanelsCoexistWithoutCrashing() {
        openEditor().use { scenario ->
            try {
                tap("😀"); tap("😂")
                scenario.onActivity { assertTrue(it.editor.text.toString().endsWith("😂")) }
                capture("phormi-emoji-smoke")
                tap("ABC"); tap("Write")
                assertTrue(device.wait(Until.hasObject(By.text("Fix grammar")), 5_000))
                capture("phormi-writing-smoke")
                tap("← Keyboard · Writing assistant")
                scenario.onActivity { activity ->
                    activity.editor.inputType = android.text.InputType.TYPE_CLASS_PHONE
                    activity.editor.setSelection(activity.editor.text.length)
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(activity.editor)
                }
                tap("1"); tap("2"); tap("⌫")
                scenario.onActivity { assertTrue(it.editor.text.toString().endsWith("1")) }
                capture("phormi-phone-smoke")
            } finally { capture("phormi-panel-final") }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<android.view.View>(R.id.btn_home).performClick() }
            val search = device.wait(Until.findObject(By.res("com.uong.phormi", "start_page_search")), 5_000)
            assertNotNull(search); search!!.click()
            assertTrue(device.wait(Until.hasObject(By.text("Space").clazz("android.widget.Button")), 8_000))
            capture("phormi-browser-keyboard-smoke")
        }
    }
}
