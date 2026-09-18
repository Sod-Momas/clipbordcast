//! mDNS 服务注册与发现（协议 §2）
//! 服务类型：_clipbordcast._tcp.local.

use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use serde::Serialize;
use std::collections::HashMap;

pub const SERVICE_TYPE: &str = "_clipbordcast._tcp.local.";
pub const DEFAULT_PORT: u16 = 47610;

#[derive(Debug, Clone, Serialize)]
pub struct Peer {
    pub device_id: String,
    pub device_name: String,
    pub addr: String,
    pub port: u16,
}

pub struct Discovery {
    daemon: ServiceDaemon,
}

impl Discovery {
    /// 注册本端服务并开始浏览，发现的设备通过 on_peer 回调上报
    pub fn start<F>(
        device_id: &str,
        device_name: &str,
        port: u16,
        on_peer: F,
    ) -> Result<Self, mdns_sd::Error>
    where
        F: Fn(Peer) + Send + 'static,
    {
        let daemon = ServiceDaemon::new()?;

        // 注册本端
        let host = format!("{}.local.", device_id);
        let mut txt: HashMap<String, String> = HashMap::new();
        txt.insert("id".into(), device_id.into());
        txt.insert("name".into(), device_name.into());
        txt.insert("v".into(), crate::model::PROTOCOL_VERSION.to_string());
        txt.insert("port".into(), port.to_string());

        let info = ServiceInfo::new(
            SERVICE_TYPE,
            device_id,
            &host,
            "", // 空地址 = 自动绑定本机所有网卡
            port,
            txt,
        )?
        .enable_addr_auto();
        daemon.register(info)?;
        tracing::info!("mDNS 服务已注册：{} ({})", device_name, device_id);

        // 浏览对端
        let receiver = daemon.browse(SERVICE_TYPE)?;
        let own_id = device_id.to_string();
        std::thread::spawn(move || {
            while let Ok(event) = receiver.recv() {
                if let ServiceEvent::ServiceResolved(info) = event {
                    let id = info
                        .get_property_val_str("id")
                        .unwrap_or_default()
                        .to_string();
                    if id.is_empty() || id == own_id {
                        continue; // 跳过自己
                    }
                    let name = info
                        .get_property_val_str("name")
                        .unwrap_or("未知设备")
                        .to_string();
                    let addr = info
                        .get_addresses()
                        .iter()
                        .next()
                        .map(|a| a.to_string())
                        .unwrap_or_default();
                    let peer = Peer {
                        device_id: id,
                        device_name: name,
                        addr,
                        port: info.get_port(),
                    };
                    tracing::info!("发现设备：{} @ {}:{}", peer.device_name, peer.addr, peer.port);
                    on_peer(peer);
                }
            }
        });

        Ok(Self { daemon })
    }
}

impl Drop for Discovery {
    fn drop(&mut self) {
        let _ = self.daemon.unregister(SERVICE_TYPE);
        let _ = self.daemon.shutdown();
    }
}
