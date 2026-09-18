package dev.sothe.clipbordcast.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：感知「复制」动作。
 *
 * 原理（docs/2026-09-15 调研 §2.1）：
 * Android 10+ 后台读剪贴板被禁，但无障碍服务可以感知事件流；
 * 检测到疑似复制事件后，拉起 [ReadClipboardActivity]（透明 Activity）
 * 瞬时获焦 → 前台窗口有资格读剪贴板 → 读完立即销毁。
 *
 * 本服务不读屏、不自动点击，隐私面最小。
 */
class ClipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipA11y"
        /** 连续事件去抖间隔：复制动作常伴随一串事件，避免重复拉起 */
        private const val DEBOUNCE_MS = 800L

        @Volatile
        var isRunning = false
            private set
    }

    private var lastTriggerAt = 0L

    override fun onServiceConnected() {
        isRunning = true
        Log.i(TAG, "无障碍服务已连接")
        // 拉起前台常驻服务（NSD 注册/浏览、配对连接）
        ClipForegroundService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!looksLikeCopy(event)) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < DEBOUNCE_MS) return
        lastTriggerAt = now

        Log.d(TAG, "检测到疑似复制事件，拉起透明 Activity 读剪贴板")
        val intent = Intent(this, ReadClipboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * 复制动作的启发式判断：
     * - 文本选择菜单点击「复制/Copy」按钮（typeViewClicked，文本为「复制」）
     * - 系统复制成功 Toast 控件出现（windowContentChanged + 类名含 Toast）
     * Round 2 将按 HyperOS / AOSP 实际事件流细化特征库
     */
    private fun looksLikeCopy(event: AccessibilityEvent): Boolean {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val texts = event.text?.joinToString(" ") ?: ""
                texts.contains("复制") || texts.equals("copy", ignoreCase = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                event.className?.toString()?.contains("Toast", ignoreCase = true) == true
            }
            else -> false
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "无障碍服务已断开")
        super.onDestroy()
    }
}
