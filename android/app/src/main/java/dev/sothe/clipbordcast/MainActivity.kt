package dev.sothe.clipbordcast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sothe.clipbordcast.service.ClipAccessibilityService
import dev.sothe.clipbordcast.service.ClipForegroundService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        a11yEnabled = isA11yEnabled(),
                        onOpenA11ySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onStartService = {
                            ClipForegroundService.start(this)
                        },
                        deviceId = deviceId(),
                    )
                }
            }
        }
    }

    /** 检查本应用无障碍服务是否已开启 */
    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
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

@Composable
fun MainScreen(
    a11yEnabled: Boolean,
    onOpenA11ySettings: () -> Unit,
    onStartService: () -> Unit,
    deviceId: String,
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text("ClipBordcast", style = MaterialTheme.typography.headlineSmall)
        Text("v0.1 · Round 1 骨架", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))

        // 采集通道卡片：AppOps（推荐）/ 无障碍（兜底）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("剪贴板采集通道", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "推荐（免备案）：USB 连电脑执行一次\n" +
                        "adb shell appops set dev.sothe.clipbordcast READ_CLIPBOARD allow\n" +
                        "授权后后台直读剪贴板，无需无障碍。"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (a11yEnabled) "兜底通道：无障碍已开启 ✅"
                    else "兜底通道：无障碍未开启（MIUI 未备案应用可能被拦截，可用 adb 命令开启）"
                )
                if (!a11yEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenA11ySettings) { Text("尝试开启无障碍") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("后台服务（LAN 发现/通信）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onStartService) { Text("启动服务") }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("本机 deviceId：$deviceId", style = MaterialTheme.typography.bodySmall)
        Text(
            "提示：为保持后台存活，请在系统设置中允许本应用自启动、关闭省电限制、锁定后台。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
