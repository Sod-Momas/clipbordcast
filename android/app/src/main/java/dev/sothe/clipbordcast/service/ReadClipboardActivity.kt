package dev.sothe.clipbordcast.service

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import dev.sothe.clipbordcast.model.ClipMessage
import dev.sothe.clipbordcast.model.DedupeWindow

/**
 * 透明 Activity：瞬时获焦读剪贴板。
 *
 * Android 10+ 仅前台窗口（或默认输入法）可读剪贴板。
 * 本 Activity 全透明、无历史、读完后立即 finish，用户无感知。
 */
class ReadClipboardActivity : Activity() {

    companion object {
        private const val TAG = "ReadClip"

        /** 与前台服务共享的去重窗口（Round 2 移入 Service 单例） */
        val dedupe = DedupeWindow()

        /** 读到文本后的回调：默认仅打日志，Round 2 接入广播 */
        @Volatile
        var onTextRead: ((ClipMessage) -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 无 setContentView —— 完全不可见
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()

        if (!text.isNullOrEmpty()) {
            val msg = ClipMessage.clipText(from = deviceId(), rawText = text)
            if (dedupe.isSuppressed(msg.hash)) {
                Log.d(TAG, "回环抑制，跳过（hash ${msg.hash.take(8)}…）")
            } else {
                dedupe.record(msg.hash)
                // 日志不落明文（协议 §7）
                Log.i(TAG, "读取剪贴板成功：len=${text.length} hash=${msg.hash.take(8)}…")
                onTextRead?.invoke(msg)
            }
        } else {
            Log.d(TAG, "剪贴板为空或非文本")
        }
        finish()
        // 去掉退出动画，做到完全无感
        overridePendingTransition(0, 0)
    }

    private fun deviceId(): String {
        val prefs = getSharedPreferences("device", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }
}
