//! microhone desktop core.
//!
//! Hosts the audio receiver (see `receiver.rs`) and exposes it to the React UI
//! through Tauri commands and events. Remaining plan items (USB mode, pairing,
//! a signed virtual driver) build on top of this.

use std::sync::Mutex;

use tauri::State;

mod receiver;

use receiver::Receiver;

/// Running receiver, if any.
type ReceiverState = Mutex<Option<Receiver>>;

/// Returns the desktop core version. Exposed to the React UI via `invoke`.
#[tauri::command]
fn app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Names of available system output devices (incl. virtual ones like VB-CABLE).
#[tauri::command]
fn list_output_devices() -> Vec<String> {
    receiver::list_output_devices()
}

/// Start receiving and playing into `device` (substring match; empty = default).
#[tauri::command]
fn start_receiver(
    app: tauri::AppHandle,
    state: State<'_, ReceiverState>,
    device: Option<String>,
    port: u16,
    latency_ms: u32,
    pcm: bool,
) -> Result<(), String> {
    let mut guard = state.lock().map_err(|e| e.to_string())?;
    if let Some(mut existing) = guard.take() {
        existing.stop();
    }
    let receiver = Receiver::start(app, device, port, latency_ms, pcm)?;
    *guard = Some(receiver);
    Ok(())
}

/// Stop the receiver if it is running.
#[tauri::command]
fn stop_receiver(state: State<'_, ReceiverState>) -> Result<(), String> {
    if let Ok(mut guard) = state.lock() {
        if let Some(mut receiver) = guard.take() {
            receiver.stop();
        }
    }
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(ReceiverState::default())
        .invoke_handler(tauri::generate_handler![
            app_version,
            list_output_devices,
            start_receiver,
            stop_receiver,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
