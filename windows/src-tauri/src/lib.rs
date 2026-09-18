//! ClipBordcast Windows 端核心
//! Round 1 范围：设备身份、剪贴板监听、mDNS 发现、去重窗口、事件上报前端
//! Round 2 计划：SAS 配对（SPAKE2）、TLS 1.3 通信层（见 docs/protocol-v1.md §3/§4）

mod clipboard_watcher;
mod discovery;
mod model;

use discovery::{Discovery, DEFAULT_PORT};
use model::{content_hash, DedupeWindow};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager};

/// 全局运行时状态
pub struct AppState {
    pub device_id: String,
    pub device_name: String,
    pub dedupe: Mutex<DedupeWindow>,
    _discovery: Discovery,
}

/// 读取或生成 deviceId（持久化到 %APPDATA%/clipbordcast/device_id）
fn load_or_create_device_id(app: &AppHandle) -> String {
    let dir = app
        .path()
        .app_config_dir()
        .expect("app_config_dir 不可用");
    std::fs::create_dir_all(&dir).ok();
    let file = dir.join("device_id");
    if let Ok(id) = std::fs::read_to_string(&file) {
        let id = id.trim().to_string();
        if !id.is_empty() {
            return id;
        }
    }
    let id = uuid::Uuid::new_v4().to_string();
    std::fs::write(&file, &id).ok();
    id
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    tauri::Builder::default()
        .setup(|app| {
            let device_id = load_or_create_device_id(app.handle());
            let device_name = hostname::get()
                .map(|h| h.to_string_lossy().to_string())
                .unwrap_or_else(|_| "Windows-PC".into());
            tracing::info!("本端身份：{} ({})", device_name, device_id);

            // mDNS 发现：上报前端 "peer-found"
            let handle = app.handle().clone();
            let discovery = Discovery::start(&device_id, &device_name, DEFAULT_PORT, move |peer| {
                let _ = handle.emit("peer-found", peer);
            })
            .expect("mDNS 初始化失败");

            app.manage(AppState {
                device_id: device_id.clone(),
                device_name,
                dedupe: Mutex::new(DedupeWindow::new()),
                _discovery: discovery,
            });

            // 剪贴板监听：去重后上报前端 "clip-changed"（Round 2 接入广播）
            let handle = app.handle().clone();
            clipboard_watcher::start(move |text| {
                let hash = content_hash(&text);
                let state = handle.state::<AppState>();
                {
                    let mut dw = state.dedupe.lock().unwrap();
                    if dw.is_suppressed(&hash) {
                        tracing::debug!("回环抑制，跳过广播（hash {}…）", &hash[..8]);
                        return;
                    }
                    dw.record(&hash);
                }
                // 日志不落明文，只记 hash 前 8 位 + 长度（协议 §7）
                tracing::info!("剪贴板变化：len={} hash={}…", text.len(), &hash[..8]);
                let msg = model::ClipMessage::new_clip_text(&state.device_id, text);
                let _ = handle.emit("clip-changed", &msg);
                // TODO(Round 2): 向所有已配对设备发送 msg
            });

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("运行 ClipBordcast 出错");
}
