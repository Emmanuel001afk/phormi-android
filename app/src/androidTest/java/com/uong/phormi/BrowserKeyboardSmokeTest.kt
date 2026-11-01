package com.uong.phormi

import android.content.Context
import android.content.Intent
import android.text.InputType
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserKeyboardSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun enableKeyboard() {
        context.getSharedPreferences("phormi_keyboard", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("phormi_keyboard_pending", Context.MODE_PRIVATE).edit().clear().commit()
        device.executeShellCommand("ime enable com.uong.phormi/.PhormiKeyboardServiceV2")
        device.executeShellCommand("ime set com.uong.phormi/.PhormiKeyboardServiceV2")
    }
    private fun editor(type: Int): ActivityScenario<KeyboardTestActivity> = ActivityScenario.launch(
        Intent(context, KeyboardTestActivity::class.java).putExtra("input_type", type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    @Test fun phoneAndDateEditorsHaveBackspaceAndDone() {
        for (type in listOf(InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)) {
            editor(type).use { scenario ->
                val reachable = device.wait(Until.hasObject(By.desc("Backspace")), 10000)
                if (!reachable) {
                    var state = ""
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val service = PhormiKeyboardServiceV2.activeInstance()
                        state = "type=$type shown=${service?.isInputViewShown} editor=${service?.currentInputEditorInfo?.inputType}"
                    }
                    val controls = device.findObjects(By.clazz("android.widget.Button")).takeLast(20).joinToString { "${it.text}/${it.contentDescription}/${it.visibleBounds}" }
                    fail("Backspace must be reachable: $state; controls=$controls")
                }
                assertTrue("Done must be reachable", device.wait(Until.hasObject(By.text("Done")), 2000))
                device.findObject(By.text("1")).click()
                device.findObject(By.desc("Backspace")).click()
                scenario.onActivity { assertEquals("", it.editor.text.toString()) }
            }
        }
    }
    @Test fun backspaceDeletesWholeEmojiCluster() {
        editor(InputType.TYPE_CLASS_TEXT).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Backspace")), 7000))
            scenario.onActivity { it.editor.setText("A👍🏽"); it.editor.setSelection(it.editor.length()) }
            device.findObject(By.desc("Backspace")).click()
            scenario.onActivity { assertEquals("A", it.editor.text.toString()) }
        }
    }
    @Test fun imageClipboardNeverPastesItsLabel() {
        context.getSharedPreferences("phormi_keyboard_clipboard", Context.MODE_PRIVATE).edit().clear().commit()
        PhormiKeyboardClipboardStore.recordUri(context, android.net.Uri.parse("content://com.uong.phormi.fileprovider/test.png"), "image/png", "Image fixture")
        editor(InputType.TYPE_CLASS_TEXT).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("📋")), 7000))
            device.findObject(By.text("📋")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Image fixture")), 3000))
            device.findObject(By.text("Image fixture")).click()
            scenario.onActivity { assertEquals("", it.editor.text.toString()) }
        }
    }
    @Test fun continuousGlideProducesAComposingWordAndBackspaceUndoesIt() {
        editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Backspace")), 7000))
            val points = "helo".map { letter ->
                val key = device.findObject(By.desc(java.util.regex.Pattern.compile(letter.toString(), java.util.regex.Pattern.CASE_INSENSITIVE)))
                assertNotNull("Letter $letter is visible", key)
                val bounds = key.visibleBounds
                android.graphics.Point(bounds.centerX(), bounds.centerY())
            }.toTypedArray()
            assertTrue(device.swipe(points, 12))
            val recognized = device.wait(Until.hasObject(By.desc("Glide candidate Hello")), 10000)
            if (!recognized) {
                var text = ""
                scenario.onActivity { text = it.editor.text.toString() }
                val choices = device.findObjects(By.clazz("android.widget.Button")).take(6).joinToString { it.contentDescription.orEmpty() }
                fail("Glide expected Hello; editor=$text; candidates=$choices")
            }
            scenario.onActivity { assertEquals("Hello", it.editor.text.toString()) }
            device.findObject(By.desc("Backspace")).click()
            scenario.onActivity { assertEquals("", it.editor.text.toString()) }
        }
    }
    @Test fun koreanKeysComposeSyllablesAndBackspaceReversesOneJamo() {
        PhormiKeyboardPreferences.setLanguage(context, "ko")
        editor(InputType.TYPE_CLASS_TEXT).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("ㅎ")), 7000))
            for (letter in "ㅎㅏㄴㄱㅡㄹ") device.findObject(By.desc(letter.toString())).click()
            scenario.onActivity { assertEquals("한글", it.editor.text.toString()) }
            device.findObject(By.desc("Backspace")).click()
            scenario.onActivity { assertEquals("한그", it.editor.text.toString()) }
            device.findObject(By.desc("Backspace")).click()
            scenario.onActivity { assertEquals("한ㄱ", it.editor.text.toString()) }
            // Moving the cursor ends composition: typing must not replace the previous word.
            scenario.onActivity { it.editor.setSelection(0) }
            device.waitForIdle()
            device.findObject(By.desc("ㅁ")).click()
            scenario.onActivity { assertEquals("ㅁ한ㄱ", it.editor.text.toString()) }
        }
    }
    @Test fun keyboardSettingsOpensWithCompatibleTheme() {
        ActivityScenario.launch(PhormiKeyboardSettingsActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("Phormi Keyboard")), 7000))
        }
    }
    @Test fun pinnedClipboardSurvivesExpiry() {
        val old = System.currentTimeMillis() - 2 * 24 * 60 * 60_000L
        context.getSharedPreferences("phormi_keyboard_clipboard", Context.MODE_PRIVATE).edit().putString("items_v3",
            """[{"text":"Keep me","pinned":true,"createdAt":$old},{"text":"Expire me","pinned":false,"createdAt":$old}]""").commit()
        assertEquals(listOf("Keep me"), PhormiKeyboardClipboardStore.list(context).map { it.text })
    }
    @Test fun tabGroupingControlIsActuallyAttached() {
        context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE).edit()
            .putString("tab_urls", "[\"https://example.com\"]").putString("tab_titles", "[\"Fixture tab\"]")
            .putString("tab_ids", "[1]").putString("tab_view_mode", "vertical").commit()
        ActivityScenario.launch(TabsOverviewActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.desc("Group Fixture tab")), 7000))
            val group = device.findObject(By.desc("Group Fixture tab"))
            assertTrue(group.visibleBounds.height() > 0)
            assertTrue(device.findObject(By.desc("Open Fixture tab")).visibleBounds.height() > 0)
        }
    }
}
