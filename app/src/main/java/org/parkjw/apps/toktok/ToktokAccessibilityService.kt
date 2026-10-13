package org.parkjw.apps.toktok

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo

/**
 * Detects keyboard visibility (focused EditText in the active window) and
 * inserts transcribed text into the focused EditText via ACTION_SET_TEXT.
 */
class ToktokAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ToktokAccessibilityService? = null
            private set

        @Volatile var isKeyboardVisible = false
            private set

        /** Called on the main thread when keyboard visibility changes. */
        var keyboardListener: ((Boolean) -> Unit)? = null

        fun insertText(text: String): Boolean =
            instance?.insertIntoFocused(text) ?: false

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ToktokAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        instance = this
        // Safety net: poll IME window state every 1.5s. Event-based detection
        // can miss keyboard dismissal on some devices (stuck-visible button).
        pollHandler.post(pollKeyboard)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        pollHandler.removeCallbacks(pollKeyboard)
        return super.onUnbind(intent)
    }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollKeyboard = object : Runnable {
        override fun run() {
            val visible = isKeyboardActuallyVisible()
            if (visible != isKeyboardVisible) {
                isKeyboardVisible = visible
                keyboardListener?.invoke(visible)
            }
            pollHandler.postDelayed(this, 1500)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        val visible = isKeyboardActuallyVisible()
        if (visible != isKeyboardVisible) {
            isKeyboardVisible = visible
            keyboardListener?.invoke(visible)
        }
    }

    override fun onInterrupt() {}

    /**
     * True only when the IME (keyboard) window is actually showing AND
     * there is a focused EditText. Checking the focused EditText alone is
     * not enough — focus can remain after the keyboard is dismissed.
     */
    private fun isKeyboardActuallyVisible(): Boolean {
        // 1) Is there an input-method window with non-empty bounds on screen?
        var imeVisible = false
        try {
            val windows = windows ?: emptyList()
            val bounds = Rect()
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    w.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        imeVisible = true
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (!imeVisible) return false
        // 2) Is there a focused EditText to type into?
        val root = rootInActiveWindow ?: return false
        val hasFocus = hasFocusedEditText(root)
        root.recycle()
        return hasFocus
    }

    private fun hasFocusedEditText(node: AccessibilityNodeInfo): Boolean {
        if (node.className == "android.widget.EditText" && node.isFocused) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = hasFocusedEditText(child)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun insertIntoFocused(text: String): Boolean {
        val target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        try {
            if (!target.isEditable || isPassword(target)) return false
            // 1) 커서 위치에 삽입: 클립보드 + ACTION_PASTE
            if (target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("toktok", text))
                if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
            }
            // 2) 폴백: 전체 교체
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            target.recycle()
        }
    }

    private fun isPassword(node: AccessibilityNodeInfo): Boolean {
        val variation = node.inputType and EditorInfo.TYPE_MASK_VARIATION
        return variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }
}
