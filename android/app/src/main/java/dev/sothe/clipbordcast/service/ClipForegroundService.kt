package dev.sothe.clipbordcast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.sothe.clipbordcast.MainActivity
import dev.sothe.clipbordcast.R
import dev.sothe.clipbordcast.net.NsdHelper

/**
 * 前台常驻服务：
 * - 持有 NSD 注册/浏览（mDNS 发现，协议 §2）
 * - **主采集通道**：系统剪贴板变化监听 + 后台直读（需 AppOps 授权，见下文）
 * - Round 2 将在此维护已配对设备的 TLS 长连接
 *
 * 采集授权（一次性 adb，免 ICP 备案、免无障碍）：
 *   adb shell appops set dev.sothe.clipbordcast READ_CLIPBOARD allow
 * 未授权时后台读剪贴板返回空，自动退化为无障碍+透明Activity通道。
 *
 * 保活依赖用户引导三件套：自启动 / 无限制省电 / 锁后台。
 */
class ClipForegroundService : Service() {

    companion object {
        private const val TAG = "ClipFgService"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ClipForegroundService::class.java)
            context.startForegroundService(intent)
        }
    }

    private lateinit var nsd: NsdHelper
    private lateinit var clipboardManager: ClipboardManager

    /** 系统剪贴板变化监听：AppOps 授权后后台回调里可直接读到内容 */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrEmpty()) {
            // 未授予 READ_CLIPBOARD 时后台读到空 —— 走无障碍兜底通道
            Log.d(TAG, "后台读剪贴板为空（未授权 AppOps？），等待无障碍通道")
            return@OnPrimaryClipChangedListener
        }
        ClipDispatcher.dispatch(this, text)
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()

        // 主采集通道：剪贴板监听（AppOps 授权后全后台生效）
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)

        nsd = NsdHelper(
            context = this,
            onPeerFound = { peer ->
                Log.i(TAG, "发现设备：${peer.deviceName} @ ${peer.host}:${peer.port}")
                // Round 1：仅记录；Round 2：区分已配对/待配对并发起连接
            }
        )
        nsd.start()
        Log.i(TAG, "前台服务已启动：剪贴板监听 + NSD 注册完成")
    }

    private fun startForegroundWithNotification() {
        val channelId = getString(R.string.notif_channel_id)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_MIN)
        )

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        nsd.stop()
        Log.i(TAG, "前台服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
