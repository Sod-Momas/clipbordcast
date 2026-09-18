package dev.sothe.clipbordcast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机自动拉起前台服务 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "开机完成，拉起前台服务")
            runCatching { ClipForegroundService.start(context) }
                .onFailure { Log.e("BootReceiver", "拉起失败", it) }
        }
    }
}
