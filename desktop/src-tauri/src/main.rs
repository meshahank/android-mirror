use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::Serialize;
use std::{collections::HashMap, time::{Duration, Instant}};

#[derive(Serialize)] struct Device { name: String, host: String, port: u16, id: String }
#[tauri::command]
fn discover_devices() -> Result<Vec<Device>, String> {
    let mdns = ServiceDaemon::new().map_err(|e| e.to_string())?;
    let receiver = mdns.browse("_android-mirror._tcp.local.").map_err(|e| e.to_string())?;
    let until = Instant::now() + Duration::from_secs(3);
    let mut found: HashMap<String, Device> = HashMap::new();
    while Instant::now() < until { if let Ok(ServiceEvent::ServiceResolved(info)) = receiver.recv_timeout(Duration::from_millis(250)) {
        if let Some(ip) = info.get_addresses().iter().find(|a| a.is_ipv4()) { let key = info.get_fullname().to_string(); found.insert(key, Device { name: info.get_fullname().trim_end_matches("._android-mirror._tcp.local.").to_string(), host: ip.to_string(), port: info.get_port(), id: info.get_property_val_str("id").unwrap_or("unknown").to_string() }); }
    }}
    let _ = mdns.shutdown(); Ok(found.into_values().collect())
}
fn main() { tauri::Builder::default().plugin(tauri_plugin_opener::init()).invoke_handler(tauri::generate_handler![discover_devices]).run(tauri::generate_context!()).expect("Tauri error"); }
