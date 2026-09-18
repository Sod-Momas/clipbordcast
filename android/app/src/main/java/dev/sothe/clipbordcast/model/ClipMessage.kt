package dev.sothe.clipbordcast.model

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** 协议常量与消息模型 —— 对应 docs/protocol-v1.md §5/§6 */
object Protocol {
    const val VERSION = 1
    const val MAX_TEXT_BYTES = 64 * 1024
    const val DEDUPE_WINDOW_MS = 5_000L
    const val MSG_TTL_MS = 60_000L
    const val SERVICE_TYPE = "_clipbordcast._tcp."
    const val DEFAULT_PORT = 47610
}

enum class MsgType { CLIP_TEXT, PING, PONG }

data class ClipMessage(
    val msgId: String = UUID.randomUUID().toString(),
    val from: String,
    val ts: Long = System.currentTimeMillis(),
    val type: MsgType,
    val hash: String,
    val text: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", Protocol.VERSION)
        .put("msgId", msgId)
        .put("from", from)
        .put("ts", ts)
        .put("type", type.name)
        .put("hash", hash)
        .put("payload", JSONObject().put("text", text))

    companion object {
        /** 发送前构造：超限截断 + 计算 hash（协议 §5.3） */
        fun clipText(from: String, rawText: String): ClipMessage {
            val text = truncateUtf8(rawText, Protocol.MAX_TEXT_BYTES)
            return ClipMessage(from = from, type = MsgType.CLIP_TEXT, hash = sha256Hex(text), text = text)
        }

        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun truncateUtf8(s: String, maxBytes: Int): String {
            val bytes = s.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return s
            // 回退到完整 UTF-8 字符边界
            var end = maxBytes
            while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
            return String(bytes, 0, end, Charsets.UTF_8)
        }
    }
}

/**
 * 防回环抑制窗口（协议 §6.2）：
 * 本端发送过 / 接收写入过的 hash，窗口期内再次出现时不再广播。
 */
class DedupeWindow(private val windowMs: Long = Protocol.DEDUPE_WINDOW_MS) {
    private val entries = ArrayDeque<Pair<String, Long>>() // hash -> 记录时刻

    @Synchronized
    fun record(hash: String) {
        evict()
        entries.addLast(hash to System.currentTimeMillis())
    }

    @Synchronized
    fun isSuppressed(hash: String): Boolean {
        evict()
        return entries.any { it.first == hash }
    }

    private fun evict() {
        val now = System.currentTimeMillis()
        while (entries.isNotEmpty() && now - entries.first().second > windowMs) {
            entries.removeFirst()
        }
    }
}
