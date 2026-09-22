package dev.sothe.clipbordcast.service

import android.content.Context
import android.util.Log
import dev.sothe.clipbordcast.model.ClipMessage
import dev.sothe.clipbordcast.model.DedupeWindow

/**
 * 剪贴板内容分发中枢：采集端（AppOps 直读 / 无障碍+透明Activity）统一入口。
 * 负责去重防回环，之后交给广播回调（Round 2 接入 TLS 发送）。
 */
object ClipDispatcher {
    private const val TAG = "ClipDispatcher"

    private val dedupe = DedupeWindow()

    /** 读到文本后的回调：默认仅打日志，Round 2 接入配对设备广播 */
    @Volatile
    var onTextRead: ((ClipMessage) -> Unit)? = null

    /** 采集到剪贴板文本后的统一处理：截断、hash、去重、分发 */
    fun dispatch(context: Context, rawText: String) {
        if (rawText.isEmpty()) return
        val msg = ClipMessage.clipText(from = deviceId(context), rawText = rawText)
        if (dedupe.isSuppressed(msg.hash)) {
            Log.d(TAG, "回环抑制，跳过（hash ${msg.hash.take(8)}…）")
            return
        }
        dedupe.record(msg.hash)
        // 日志不落明文（协议 §7）
        Log.i(TAG, "剪贴板采集成功：len=${rawText.length} hash=${msg.hash.take(8)}…")
        onTextRead?.invoke(msg)
    }

    /** 收到远端消息后写入本端剪贴板前，先把 hash 记入抑制窗口（协议 §6.2） */
    fun suppressNext(hash: String) = dedupe.record(hash)

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }
}
