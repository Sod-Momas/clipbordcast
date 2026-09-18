package dev.sothe.clipbordcast.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.sothe.clipbordcast.model.Protocol
import java.util.UUID

/** mDNS 注册与浏览（Android NSD 实现，协议 §2） */
class NsdHelper(
    private val context: Context,
    private val onPeerFound: (Peer) -> Unit,
) {
    companion object {
        private const val TAG = "NsdHelper"
    }

    data class Peer(
        val deviceId: String,
        val deviceName: String,
        val host: String,
        val port: Int,
    )

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registered = false

    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: Build.MODEL ?: "Android"

    fun start() {
        registerService()
        discoverServices()
    }

    fun stop() {
        runCatching {
            if (registered) nsdManager.unregisterService(registrationListener)
            nsdManager.stopServiceDiscovery(discoveryListener)
        }
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = "cbcc-$deviceId"
            serviceType = Protocol.SERVICE_TYPE
            port = Protocol.DEFAULT_PORT
            // TXT 记录（协议 §2）：id / name / v / port
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
            setAttribute("v", Protocol.VERSION.toString())
            setAttribute("port", Protocol.DEFAULT_PORT.toString())
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun discoverServices() {
        nsdManager.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registered = true
            Log.i(TAG, "NSD 已注册：${info.serviceName}")
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "NSD 注册失败：errorCode=$errorCode")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) { registered = false }
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceName.contains(deviceId)) return // 跳过自己
            nsdManager.resolveService(info, resolveListener)
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            Log.d(TAG, "设备离线：${info.serviceName}")
        }
        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "NSD 浏览已开始：$serviceType")
        }
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "NSD 浏览启动失败：errorCode=$errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "解析失败：${info.serviceName} errorCode=$errorCode")
        }
        override fun onServiceResolved(info: NsdServiceInfo) {
            val id = info.attributes["id"]?.let { String(it, Charsets.UTF_8) } ?: return
            if (id == deviceId) return
            val name = info.attributes["name"]?.let { String(it, Charsets.UTF_8) } ?: "未知设备"
            val host = info.host?.hostAddress ?: return
            onPeerFound(Peer(deviceId = id, deviceName = name, host = host, port = info.port))
        }
    }
}
