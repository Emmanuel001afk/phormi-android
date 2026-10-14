package com.uong.phormi

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android IME window, editor connection and cross-panel smoke test on the API 35 emulator. */
@RunWith(AndroidJUnit4::class)
class KeyboardIntegrationSmokeTest {
    @Test fun keyboardInsidePhormiTypesUndoesAndSwitchesPanelsWithoutCrashing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE).edit().putBoolean("news_enabled", false).commit()
        context.getSharedPreferences("phormi_keyboard", Context.MODE_PRIVATE).edit().clear().putBoolean("haptic", false).putBoolean("sound", false).commit()
        device.executeShellCommand("ime enable com.uong.phormi/.PhormiKeyboardServiceV2")
        device.executeShellCommand("ime set com.uong.phormi/.PhormiKeyboardServiceV2")
        val intent = Intent(context, KeyboardSmokeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<KeyboardSmokeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.editor.requestFocus()
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(activity.editor, InputMethodManager.SHOW_IMPLICIT)
            }
            fun tap(label: String) {
                val key = device.wait(Until.findObject(By.text(label).clazz("android.widget.Button")), 8_000)
                assertNotNull("Keyboard control not visible: $label", key)
                key!!.click(); device.waitForIdle()
            }
            fun expect(value: String) { scenario.onActivity { assertEquals(value, it.editor.text.toString()) } }
            tap("H"); tap("e"); tap("l"); tap("l"); tap("o"); tap("Space")
            expect("Hello ")
            tap("t"); tap("e"); tap("h"); tap("Space")
            expect("Hello the ")
            tap("⌫"); expect("Hello teh")
            tap("Space"); expect("Hello teh ")
            tap("😀")
            tap("😂")
            scenario.onActivity { assertTrue(it.editor.text.toString().endsWith("😂")) }
            device.takeScreenshot(File(context.getExternalFilesDir(null), "phormi-emoji-smoke.png"))
            tap("ABC")
            tap("Write")
            assertTrue(device.wait(Until.hasObject(By.text("Fix grammar")), 5_000))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "phormi-writing-smoke.png"))
            tap("← Keyboard · Writing assistant")
            scenario.onActivity { activity ->
                activity.editor.inputType = android.text.InputType.TYPE_CLASS_PHONE
                activity.editor.setSelection(activity.editor.text.length)
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(activity.editor)
            }
            tap("1"); tap("2"); tap("⌫")
            scenario.onActivity { assertTrue(it.editor.text.toString().endsWith("1")) }
            device.takeScreenshot(File(context.getExternalFilesDir(null), "phormi-phone-smoke.png"))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.findViewById<android.view.View>(R.id.btn_home).performClick() }
            val search = device.wait(Until.findObject(By.res("com.uong.phormi", "start_page_search")), 5_000)
            assertNotNull(search); search!!.click()
            assertTrue(device.wait(Until.hasObject(By.text("Space").clazz("android.widget.Button")), 8_000))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "phormi-browser-keyboard-smoke.png"))
        }
    }
}
