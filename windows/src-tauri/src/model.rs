//! 协议数据模型与去重窗口
//! 对应 docs/protocol-v1.md §5 / §6

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::VecDeque;
use std::time::{Duration, Instant};

pub const PROTOCOL_VERSION: u32 = 1;
/// 单条文字上限 64KB（协议 §1）
pub const MAX_TEXT_BYTES: usize = 64 * 1024;
/// 去重/防回环窗口 5 秒（协议 §6）
pub const DEDUPE_WINDOW: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipMessage {
    pub v: u32,
    #[serde(rename = "msgId")]
    pub msg_id: String,
    pub from: String,
    pub ts: i64,
    #[serde(rename = "type")]
    pub msg_type: MsgType,
    pub hash: String,
    pub payload: ClipPayload,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipPayload {
    pub text: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MsgType {
    ClipText,
    Ping,
    Pong,
}

/// sha256(utf8(text)) 全小写 hex —— 协议 §5.3
pub fn content_hash(text: &str) -> String {
    let mut h = Sha256::new();
    h.update(text.as_bytes());
    hex_lower(&h.finalize())
}

fn hex_lower(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

impl ClipMessage {
    pub fn new_clip_text(from: &str, text: String) -> Self {
        let text = truncate_to_limit(text);
        Self {
            v: PROTOCOL_VERSION,
            msg_id: uuid::Uuid::new_v4().to_string(),
            from: from.to_string(),
            ts: now_millis(),
            msg_type: MsgType::ClipText,
            hash: content_hash(&text),
            payload: ClipPayload { text },
        }
    }
}

pub fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn truncate_to_limit(text: String) -> String {
    if text.len() <= MAX_TEXT_BYTES {
        return text;
    }
    // 按 char 边界安全截断，避免切坏 UTF-8
    let mut end = MAX_TEXT_BYTES;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    tracing::warn!("剪贴板文本超过 64KB，已截断（原始 {} 字节）", text.len());
    text[..end].to_string()
}

/// 防回环抑制窗口（协议 §6.2）：
/// 本端「发送过」或「接收写入过」的 hash 在窗口期内再次出现时不广播。
pub struct DedupeWindow {
    entries: VecDeque<(String, Instant)>,
}

impl DedupeWindow {
    pub fn new() -> Self {
        Self {
            entries: VecDeque::new(),
        }
    }

    /// 记录一个 hash（发送前 / 接收写入后调用）
    pub fn record(&mut self, hash: &str) {
        self.evict();
        self.entries.push_back((hash.to_string(), Instant::now()));
    }

    /// 判断该 hash 是否处于抑制窗口内
    pub fn is_suppressed(&mut self, hash: &str) -> bool {
        self.evict();
        self.entries.iter().any(|(h, _)| h == hash)
    }

    fn evict(&mut self) {
        let now = Instant::now();
        while let Some((_, t)) = self.entries.front() {
            if now.duration_since(*t) > DEDUPE_WINDOW {
                self.entries.pop_front();
            } else {
                break;
            }
        }
    }
}

impl Default for DedupeWindow {
    fn default() -> Self {
        Self::new()
    }
}
