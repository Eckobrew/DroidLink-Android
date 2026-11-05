package com.droiddock.companion.mirroring

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/** Accepts input only from the authenticated, currently approved sharing session. */
class MirrorAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: MirrorAccessibilityService? = null; private set }
    private val main = Handler(Looper.getMainLooper())
    private var down = false
    private var busy = false
    private var x = 0f
    private var y = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var stroke: GestureDescription.StrokeDescription? = null
    private var generation = 0
    private var awakeOverlay: android.view.View? = null
    private var keepAwake = true
    private var dimmed = false
    override fun onServiceConnected() { instance = this; updateAwakeOverlay() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { releaseTouch() }
    override fun onDestroy() { removeAwakeOverlay(); instance = null; releaseTouch(); super.onDestroy() }

    fun input(sessionId: String, command: JSONObject) {
        main.post {
            if (MirrorCoordinator.activeSession != sessionId) return@post
            when (command.optString("action")) {
                "down", "move", "up" -> {
                    val nx = command.optDouble("x", Double.NaN)
                    val ny = command.optDouble("y", Double.NaN)
                    if (!nx.isFinite() || !ny.isFinite()) return@post
                    val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                    x = (nx.coerceIn(0.0, 1.0) * (bounds.width() - 1)).toFloat()
                    y = (ny.coerceIn(0.0, 1.0) * (bounds.height() - 1)).toFloat()
                    when (command.optString("action")) {
                        "down" -> { if (down) return@post; down = true }
                        "move" -> if (!down) return@post
                        "up" -> down = false
                    }
                    pumpTouch()
                }
                "keepAwake" -> { keepAwake = command.optBoolean("enabled", true); updateAwakeOverlay() }
                "dim" -> { dimmed = command.optBoolean("enabled", false); updateAwakeOverlay() }
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "text" -> editText(command.optString("text").take(4096), false)
                "delete" -> editText("", true)
                "enter" -> rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                "selectAll" -> {
                    val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (node != null && !node.isPassword) node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
                    })
                }
                "cursor" -> {
                    val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    val text = node?.text?.toString() ?: ""
                    if (node != null && !node.isPassword) {
                        val current = node.textSelectionEnd.coerceIn(0, text.length)
                        val direction = command.optInt("direction").coerceIn(-1, 1)
                        val next = if ((direction < 0 && current > 0) || (direction > 0 && current < text.length)) text.offsetByCodePoints(current, direction) else current
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next)
                        })
                    }
                }
                "scroll" -> {
                    releaseTouch()
                    val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                    val sx = bounds.width() * 0.5f; val sy = bounds.height() * 0.5f
                    val distance = command.optDouble("dy", 0.0).coerceIn(-0.4, 0.4).toFloat() * bounds.height()
                    if (distance != 0f) {
                        val path = Path().apply { moveTo(sx, sy); lineTo(sx, sy - distance) }
                        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 140)).build(), null, null)
                    }
                }
            }
        }
    }

    fun sessionChanged(active: Boolean) {
        main.post {
            if (active) { keepAwake = true; dimmed = false; updateAwakeOverlay() }
            else { removeAwakeOverlay(); releaseTouch() }
        }
    }
    private fun removeAwakeOverlay() {
        awakeOverlay?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }
        awakeOverlay = null
    }
    private fun updateAwakeOverlay() {
        if (MirrorCoordinator.activeSession == null || !keepAwake) { removeAwakeOverlay(); return }
        val manager = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            screenBrightness = if (dimmed) 0.01f else -1f
            title = "Droid Link keep awake"
        }
        try {
            if (awakeOverlay == null) {
                val view = android.view.View(this).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) }
                manager.addView(view, params); awakeOverlay = view
            } else manager.updateViewLayout(awakeOverlay, params)
        } catch (e: Exception) { android.util.Log.w("DroidLinkMirror", "Could not keep phone awake", e) }
    }

    fun launchApp(sessionId: String, packageName: String) {
        main.post {
            if (MirrorCoordinator.activeSession != sessionId) return@post
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(it) } catch (_: Exception) {}
            }
        }
    }

    private fun editText(insert: String, delete: Boolean) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!node.isEditable || node.isPassword) return
        val text = node.text?.toString() ?: ""
        val start = node.textSelectionStart.coerceIn(0, text.length)
        val end = node.textSelectionEnd.coerceIn(start, text.length)
        val from = if (delete && start == end && start > 0) text.offsetByCodePoints(start, -1) else start
        val updated = text.substring(0, from) + insert + text.substring(end)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, from + insert.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, from + insert.length)
        })
    }

    private fun pumpTouch() {
        if (busy || (stroke == null && !down)) return
        val previous = stroke
        if (previous != null && down && x == lastX && y == lastY) return
        val path = Path().apply {
            moveTo(if (previous == null) x else lastX, if (previous == null) y else lastY)
            if (previous != null) lineTo(x, y)
        }
        val next = previous?.continueStroke(path, 0, 8, down) ?: GestureDescription.StrokeDescription(path, 0, 8, down)
        val continues = down
        lastX = x; lastY = y
        busy = true
        val token = generation
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (token != generation) return
                busy = false
                stroke = if (continues) next else null
                pumpTouch()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (token != generation) return
                busy = false; stroke = null; down = false
            }
        }
        if (!dispatchGesture(GestureDescription.Builder().addStroke(next).build(), callback, main)) {
            busy = false; stroke = null; down = false
        }
    }

    fun releaseTouch() {
        main.post {
            down = false
            if (busy) return@post // Callback completes the continuation with a final up event.
            pumpTouch()
        }
    }
}
