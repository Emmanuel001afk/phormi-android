package com.uong.phormi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Browser AI's eyes and hands.
 *
 * The service exposes visible UI structure and safe interaction primitives.
 * Password/PIN/credential inputs are deliberately redacted from the AI and
 * cannot be filled through the automation channel; the user must complete
 * those sensitive fields manually.
 */
class PhormiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PhormiAccessibility"

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
        // Screen data is read on demand by the AI controller.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "[]"
        val results = JSONArray()
        collectNodes(root, results)
        return results.toString()
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: JSONArray) {
        try {
            val sensitive = isSensitiveInput(node)
            val hasText = !node.text.isNullOrBlank()
            val hasDesc = !node.contentDescription.isNullOrBlank()
            if (hasText || hasDesc || node.isClickable || node.isEditable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val obj = JSONObject()
                obj.put("text", when {
                    sensitive -> "[SENSITIVE INPUT REDACTED]"
                    hasText -> node.text?.toString().orEmpty()
                    else -> node.contentDescription?.toString().orEmpty()
                })
                obj.put("className", node.className?.toString() ?: "")
                obj.put("clickable", node.isClickable)
                obj.put("editable", node.isEditable)
                obj.put("sensitive", sensitive)
                obj.put("centerX", bounds.centerX())
                obj.put("centerY", bounds.centerY())
                out.put(obj)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading node: ${e.message}")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, out) }
        }
    }

    private fun isSensitiveInput(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEditable) return false
        val type = node.inputType
        val passwordMask = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val visiblePasswordMask = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val webPasswordMask = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        if ((type and passwordMask) == passwordMask ||
            (type and visiblePasswordMask) == visiblePasswordMask ||
            (type and webPasswordMask) == webPasswordMask) return true

        val clue = listOfNotNull(
            node.hintText?.toString(),
            node.contentDescription?.toString(),
            node.viewIdResourceName
        ).joinToString(" ").lowercase()
        return listOf("password", "passcode", "pin", "security code", "otp", "one-time code", "cvv", "cvc", "secret").any(clue::contains)
    }

    /** Types only into a non-sensitive focused editable field. */
    fun typeIntoFocusedField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root) ?: return false
        if (isSensitiveInput(focused)) return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
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

    fun tapAt(x: Int, y: Int, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone?.invoke(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone?.invoke(false) }
        }, null)
    }

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
