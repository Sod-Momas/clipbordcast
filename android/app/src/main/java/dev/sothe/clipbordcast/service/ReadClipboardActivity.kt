package dev.sothe.clipbordcast.service

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * 透明 Activity：瞬时获焦读剪贴板。
 *
 * 兜底采集通道：未授予 AppOps READ_CLIPBOARD 时，
 * 由无障碍服务感知到复制事件后拉起本 Activity 读剪贴板。
 * 全透明、无历史、读完立即 finish，用户无感知。
 */
class ReadClipboardActivity : Activity() {

    companion object {
        private const val TAG = "ReadClip"
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
            ClipDispatcher.dispatch(this, text)
        } else {
            Log.d(TAG, "剪贴板为空或非文本")
        }
        finish()
        // 去掉退出动画，做到完全无感
        overridePendingTransition(0, 0)
    }
}
