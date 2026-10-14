package com.uong.phormi

import android.app.Application
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PhormiKeyboardSurfaceTest {
    private lateinit var service: PhormiKeyboardServiceV2
    @Before fun setup() { service = Robolectric.buildService(PhormiKeyboardServiceV2::class.java).create().get() }
    @After fun cleanup() { service.onDestroy() }
    private fun buttons(view: View): List<Button> = if (view is Button) listOf(view) else if (view is ViewGroup)
        (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList()
    @Test fun `phone PIN and date layouts always retain delete and meaningful editor action`() {
        for (type in listOf(InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, InputType.TYPE_CLASS_DATETIME)) {
            service.onStartInput(EditorInfo().apply { inputType = type; imeOptions = EditorInfo.IME_ACTION_NEXT; packageName = "test" }, false)
            val labels = buttons(service.render()).map { it.text.toString() }
            assertTrue("Delete missing from $type", labels.contains("⌫"))
            assertTrue("Editor action missing from $type", labels.contains("Next"))
            assertTrue(labels.containsAll((0..9).map(Int::toString)))
        }
    }
    @Test fun `meaningful Send is honored even for multiline text`() {
        service.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; imeOptions = EditorInfo.IME_ACTION_SEND }, false)
        assertTrue(buttons(service.render()).any { it.text.toString() == "Send" })
    }
    @Test fun `no-enter-action flag keeps the newline key`() {
        service.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION }, false)
        assertTrue(buttons(service.render()).any { it.text.toString() == "↵" })
    }
    @Test fun `all panels use the same viewport height`() {
        service.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test" }, false)
        val heights = PhormiKeyboardServiceV2.Panel.entries.map { panel ->
            ReflectionHelpers.setField(service, "panel", panel)
            service.render().layoutParams?.height ?: 0
        }
        assertTrue(heights.all { it > 0 })
        assertEquals(1, heights.distinct().size)
    }
    @Test fun `docked footer reserves the system navigation area without accumulating padding`() {
        service.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test" }, false)
        val view = service.render()
        val initial = view.paddingBottom
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 24))
            .build()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(view, insets)
        assertEquals(initial + 24, view.paddingBottom)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(view, insets)
        assertEquals(initial + 24, view.paddingBottom)
    }

}
