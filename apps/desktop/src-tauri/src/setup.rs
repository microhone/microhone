//! First-run setup helper (Windows).
//!
//! `install_vbcable` downloads the free VB-CABLE virtual audio device and runs
//! its installer, so the user doesn't have to find/extract it by hand. It spawns
//! an elevated PowerShell step (one UAC prompt) and logs to
//! `%TEMP%\microhone\install.log`.
//!
//! A native "microhone Microphone" device (no VB-CABLE, our own name) needs a
//! signed kernel driver — a paid, later step. See microhone-plan.md §5.

use std::process::Command;

#[cfg(target_os = "windows")]
fn run_elevated_ps(name: &str, script: &str) -> Result<(), String> {
    let dir = std::env::temp_dir().join("microhone");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let ps1 = dir.join(name);
    std::fs::write(&ps1, script).map_err(|e| e.to_string())?;

    // A non-elevated PowerShell re-launches itself elevated (raises UAC).
    let launch = format!(
        "Start-Process powershell -Verb RunAs -ArgumentList \
         '-NoProfile','-ExecutionPolicy','Bypass','-File','{}'",
        ps1.display()
    );
    Command::new("powershell")
        .args(["-NoProfile", "-WindowStyle", "Hidden", "-Command", &launch])
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(not(target_os = "windows"))]
fn run_elevated_ps(_name: &str, _script: &str) -> Result<(), String> {
    Err("only supported on Windows".to_string())
}

const INSTALL_PS: &str = r#"
$ErrorActionPreference = 'Continue'
$dir = Join-Path $env:TEMP 'microhone'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$log = Join-Path $dir 'install.log'
"=== $(Get-Date) install start (admin) ===" | Set-Content $log
try {
    $zip = Join-Path $dir 'vbcable.zip'
    Invoke-WebRequest -Uri 'https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack43.zip' -OutFile $zip
    "downloaded" | Add-Content $log
    Expand-Archive -Path $zip -DestinationPath (Join-Path $dir 'vbcable') -Force
    "extracted" | Add-Content $log
    Start-Process -FilePath (Join-Path $dir 'vbcable\VBCABLE_Setup_x64.exe') -Wait
    "installer finished" | Add-Content $log
} catch {
    "FAILED: $_" | Add-Content $log
}
"#;

/// Download and run the VB-CABLE installer (one UAC prompt).
#[tauri::command]
pub fn install_vbcable() -> Result<(), String> {
    run_elevated_ps("install-vbcable.ps1", INSTALL_PS)
}
