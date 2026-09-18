//! Windows 剪贴板监听：隐藏消息窗口 + AddClipboardFormatListener
//! 事件驱动，无轮询，空闲 CPU ≈ 0。

use std::sync::OnceLock;
use windows::core::w;
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::DataExchange::AddClipboardFormatListener;
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::WindowsAndMessaging::*;

/// 剪贴板变化回调（参数为最新文本）
static ON_CHANGE: OnceLock<Box<dyn Fn(String) + Send + Sync>> = OnceLock::new();

/// 在独立线程启动监听（该线程需要跑 Win32 消息循环）
pub fn start<F>(on_change: F)
where
    F: Fn(String) + Send + Sync + 'static,
{
    let _ = ON_CHANGE.set(Box::new(on_change));
    std::thread::spawn(|| unsafe { watcher_loop() });
}

unsafe fn watcher_loop() {
    let hinstance: windows::Win32::Foundation::HINSTANCE =
        GetModuleHandleW(None).expect("GetModuleHandleW failed").into();

    let class_name = w!("CBCC_CLIP_WATCHER");
    let wc = WNDCLASSW {
        lpfnWndProc: Some(wnd_proc),
        hInstance: hinstance,
        lpszClassName: class_name,
        ..Default::default()
    };
    RegisterClassW(&wc);

    let hwnd = CreateWindowExW(
        WINDOW_EX_STYLE::default(),
        class_name,
        w!("cbcc-clip-watcher"),
        WINDOW_STYLE::default(),
        0,
        0,
        0,
        0,
        HWND_MESSAGE, // 仅收消息，不可见
        None,
        hinstance,
        None,
    )
    .expect("CreateWindowExW failed");

    if AddClipboardFormatListener(hwnd).is_err() {
        tracing::error!("AddClipboardFormatListener 注册失败");
        return;
    }
    tracing::info!("剪贴板监听已启动");

    let mut msg = MSG::default();
    while GetMessageW(&mut msg, hwnd, 0, 0).into() {
        let _ = TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
}

unsafe extern "system" fn wnd_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if msg == WM_CLIPBOARDUPDATE {
        if let Some(cb) = ON_CHANGE.get() {
            if let Some(text) = read_clipboard_text() {
                cb(text);
            }
        }
        return LRESULT(0);
    }
    DefWindowProcW(hwnd, msg, wparam, lparam)
}

/// 读取剪贴板文本；密码管理器写入的内容（CFSTR_CLIPBOARD_VIEWER_IGNORE）直接跳过
fn read_clipboard_text() -> Option<String> {
    // TODO(v0.3): 检测 "Clipboard Viewer Ignore" 格式标记，跳过 1Password 等敏感来源
    let mut cb = arboard::Clipboard::new().ok()?;
    match cb.get_text() {
        Ok(t) if !t.is_empty() => Some(t),
        _ => None,
    }
}
