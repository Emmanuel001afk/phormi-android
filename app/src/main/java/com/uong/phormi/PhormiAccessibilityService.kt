package com.uong.phormi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * PhormiAccessibilityService
 *
 * This is the "eyes and hands" the AI uses to read and control what's on
 * screen, without needing a separate hosted browser-automation server.
 *
 * - Reading the screen: walks the accessibility tree (the same structural
 *   data TalkBack uses for blind users) and turns it into a simple JSON
 *   list of what's visible: text, buttons, input fields, and where they
 *   are on screen.
 * - Acting on the screen: can tap a specific point, or type text into
 *   a specific field, on command.
 *
 * The AI decision-making itself (deciding WHAT to tap) does not live here.
 * This class only exposes "read the screen" and "do this action" — a
 * separate controller will call into this with instructions.
 */
class PhormiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PhormiAccessibility"

        // Simple static reference so the rest of the app can reach the
        // running service instance without needing a bound-service setup.
        @Volatile
        var instance: PhormiAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Phormi accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally left mostly empty for now — this fires constantly
        // as the screen changes. Reading happens on-demand via
        // readScreen() below, not continuously here, to avoid wasting
        // battery/CPU on a low-RAM phone.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    /**
     * Reads everything currently visible on screen and returns it as a
     * JSON array of simple objects: { text, className, bounds, clickable,
     * editable }. This is what gets handed to the AI so it can decide
     * what to do next.
     */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "[]"
        val results = JSONArray()
        collectNodes(root, results)
        return results.toString()
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: JSONArray) {
        try {
            val hasText = !node.text.isNullOrBlank()
            val hasDesc = !node.contentDescription.isNullOrBlank()
            if (hasText || hasDesc || node.isClickable || node.isEditable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                val obj = JSONObject()
                obj.put("text", node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                obj.put("className", node.className?.toString() ?: "")
                obj.put("clickable", node.isClickable)
                obj.put("editable", node.isEditable)
                obj.put("centerX", bounds.centerX())
                obj.put("centerY", bounds.centerY())
                out.put(obj)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading node: ${e.message}")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out)
        }
    }

    /** Taps at a specific screen coordinate, as if a finger touched it. */
    fun tapAt(x: Int, y: Int, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke(false)
            }
        }, null)
    }

    /** Types text into whichever field is currently focused for input. */
    fun typeIntoFocusedField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root) ?: return false

        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }
    /** Scroll the active window. direction: "up" | "down" | "left" | "right" */
    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = when (direction.lowercase()) {
            "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        if (performScrollOn(root, action)) return true
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        val path = Path()
        when (direction.lowercase()) {
            "up" -> { path.moveTo(cx, cy * 0.35f); path.lineTo(cx, cy * 1.15f) }
            "left" -> { path.moveTo(cx * 0.3f, cy); path.lineTo(cx * 1.2f, cy) }
            "right" -> { path.moveTo(cx * 1.2f, cy); path.lineTo(cx * 0.3f, cy) }
            else -> { path.moveTo(cx, cy * 1.15f); path.lineTo(cx, cy * 0.35f) }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performScrollOn(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable && node.performAction(action)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (performScrollOn(child, action)) return true
        }
        return false
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

}
