//! microhone desktop core.
//!
//! Faz 0: skeleton only. The real responsibilities of this crate (see
//! microhone-plan.md, section 10) will grow here:
//!   - mDNS advertisement (`_microhone._tcp`)
//!   - TCP control server + UDP audio server
//!   - Opus decode -> jitter buffer -> write to virtual audio device (cpal)
//!   - ADB forward management (USB mode)
//!   - pairing / encryption

/// Returns the desktop core version. Exposed to the React UI via `invoke`.
#[tauri::command]
fn app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![app_version])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
