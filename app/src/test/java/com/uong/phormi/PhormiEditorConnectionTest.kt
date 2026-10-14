package com.uong.phormi

import android.app.Application
import android.os.Looper
import android.text.Editable
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PhormiEditorConnectionTest {
    private class Editor(initial: String) : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val value: Editable = Editable.Factory.getInstance().newEditable(initial).also { Selection.setSelection(it, it.length) }
        override fun getEditable(): Editable = value
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Blocking text query on shared UI thread" }
            return super.getTextBeforeCursor(n, flags)
        }
        override fun getSelectedText(flags: Int): CharSequence? {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Blocking selection query on shared UI thread" }
            return super.getSelectedText(flags)
        }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Blocking following-text query on shared UI thread" }
            return super.getTextAfterCursor(n, flags)
        }
    }
    private fun ready(connection: PhormiEditorConnection) {
        connection.refresh(0)!!.get(3, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun `same-process reads never query the target on the UI thread`() {
        val editor = Editor("Hello ")
        val connection = PhormiEditorConnection(editor, 6, 6) {}
        try {
            ready(connection)
            assertEquals("Hello ", connection.getTextBeforeCursor(100, 0).toString())
            assertNull(connection.getSelectedText(0))
            assertEquals("", connection.getTextAfterCursor(10, 0).toString())
        } finally { connection.dispose() }
    }
    @Test fun `ordered local correction and undo do not require a synchronous editor read`() {
        val editor = Editor("Hello teh")
        val connection = PhormiEditorConnection(editor, 9, 9) {}
        try {
            ready(connection)
            connection.deleteSurroundingText(3, 0); connection.commitText("the", 1); connection.commitText(" ", 1)
            assertEquals("Hello the ", connection.getTextBeforeCursor(300, 0).toString())
            connection.deleteSurroundingText(4, 0); connection.commitText("teh", 1)
            assertEquals("Hello teh", connection.getTextBeforeCursor(300, 0).toString())
            assertEquals("Hello teh", editor.value.toString())
        } finally { connection.dispose() }
    }
    @Test fun `cursor changes invalidate old context before fresh selection can be read`() {
        val editor = Editor("Hello world")
        val connection = PhormiEditorConnection(editor, 11, 11) {}
        try {
            ready(connection)
            Selection.setSelection(editor.value, 0, 5); connection.selectionChanged(0, 5)
            assertEquals("", connection.getTextBeforeCursor(100, 0).toString())
            ready(connection)
            assertEquals("Hello", connection.getSelectedText(0).toString())
            connection.commitText("Hi", 1)
            assertEquals("Hi world", editor.value.toString())
        } finally { connection.dispose() }
    }
    @Test fun `a normal delete tap invokes correction undo once through the actual keyboard key`() {
        val service = org.robolectric.Robolectric.buildService(PhormiKeyboardServiceV2::class.java).create().get()
        val editor = Editor("Hello teh")
        try {
            org.robolectric.util.ReflectionHelpers.setField(service, "mStartedInputConnection", editor)
            service.onStartInput(android.view.inputmethod.EditorInfo().apply {
                packageName = RuntimeEnvironment.getApplication().packageName
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                initialSelStart = 9; initialSelEnd = 9
            }, false)
            ready(service.currentInputConnection as PhormiEditorConnection)
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(service, "commitSpace")
            assertEquals("Hello the ", editor.value.toString())
            fun delete(view: View): android.widget.Button? {
                if (view is android.widget.Button && view.text.toString() == "⌫") return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) delete(view.getChildAt(i))?.let { return it }
                return null
            }
            val key = requireNotNull(delete(service.render())).apply { layout(0, 0, 100, 100) }
            val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 20f, 20f, 0)
            val up = android.view.MotionEvent.obtain(0, 20, android.view.MotionEvent.ACTION_UP, 20f, 20f, 0)
            key.dispatchTouchEvent(down)
            assertEquals("Hello the ", editor.value.toString())
            key.dispatchTouchEvent(up)
            assertEquals("Hello teh", editor.value.toString())
            down.recycle(); up.recycle()
        } finally { service.onDestroy() }
    }

}
