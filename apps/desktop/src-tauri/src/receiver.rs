//! Audio receiver core for the desktop host.
//!
//! Receives the audio channel (`packages/protocol/PROTOCOL.md`), Opus-decodes
//! with PLC (or raw PCM), runs a target-latency jitter buffer, and plays into a
//! chosen output device via cpal. It also advertises `_microhone._tcp` over
//! mDNS.
//!
//! Two transports share the same decode/jitter/playback pipeline:
//!   - **WiFi:** UDP datagrams (one packet per audio frame).
//!   - **USB:** a TCP stream tunnelled through `adb reverse`, where each frame is
//!     length-prefixed (`[len:u16 BE][ frame ]`).
//!
//! Everything that touches a cpal `Stream` (which is `!Send` on Windows) lives
//! inside one dedicated thread, so the handle stored in Tauri state only carries
//! `Send` pieces (a stop flag, the join handle, the mDNS daemon).

use std::collections::VecDeque;
use std::io::{ErrorKind, Read};
use std::net::{TcpListener, UdpSocket};
use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use base64::Engine;
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use magnum_opus::{Channels, Decoder};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use tauri::{AppHandle, Emitter};

/// GCM nonce length in bytes, prepended to every encrypted packet.
const NONCE_LEN: usize = 12;

const HEADER_LEN: usize = 8;
const MAX_FRAME: usize = 4096;
const SERVICE_TYPE: &str = "_microhone._tcp.local.";

/// Names of available output devices, for the UI dropdown.
pub fn list_output_devices() -> Vec<String> {
    let host = cpal::default_host();
    match host.output_devices() {
        Ok(devices) => devices.filter_map(|d| d.name().ok()).collect(),
        Err(_) => Vec::new(),
    }
}

/// Priming jitter buffer (see the PoC for the rationale).
struct JitterBuffer {
    samples: VecDeque<f32>,
    priming: bool,
    target: usize,
    max: usize,
}

impl JitterBuffer {
    fn new(target: usize) -> Self {
        Self {
            samples: VecDeque::new(),
            priming: true,
            target,
            max: target * 4,
        }
    }

    fn push_frame<I: IntoIterator<Item = f32>>(&mut self, frame: I) {
        self.samples.extend(frame);
        if self.samples.len() > self.max {
            let overflow = self.samples.len() - self.target;
            self.samples.drain(0..overflow);
        }
    }

    fn pop(&mut self) -> f32 {
        if self.priming {
            if self.samples.len() < self.target {
                return 0.0;
            }
            self.priming = false;
        }
        match self.samples.pop_front() {
            Some(s) => s,
            None => {
                self.priming = true;
                0.0
            }
        }
    }
}

/// Decode + jitter-buffer + level-meter, shared by both transports.
struct Pipeline {
    app: AppHandle,
    buffer: Arc<Mutex<JitterBuffer>>,
    decoder: Option<Decoder>,
    last_seq: Option<u32>,
    peak: f32,
    last_emit: Instant,
}

impl Pipeline {
    fn new(app: AppHandle, buffer: Arc<Mutex<JitterBuffer>>, pcm: bool) -> Result<Self, String> {
        let decoder = if pcm {
            None
        } else {
            Some(Decoder::new(48_000, Channels::Mono).map_err(|e| e.to_string())?)
        };
        Ok(Self {
            app,
            buffer,
            decoder,
            last_seq: None,
            peak: 0.0,
            last_emit: Instant::now(),
        })
    }

    /// `pkt` is a full audio frame: `[seq:u32][timestamp:u32][payload]`.
    fn process(&mut self, pkt: &[u8], scratch: &mut [f32]) {
        if pkt.len() <= HEADER_LEN {
            return;
        }
        let seq = u32::from_be_bytes([pkt[0], pkt[1], pkt[2], pkt[3]]);
        let gap = self.last_seq.map(|prev| seq.wrapping_sub(prev)).unwrap_or(1);
        self.last_seq = Some(seq);
        let payload = &pkt[HEADER_LEN..];

        let count = match self.decoder.as_mut() {
            Some(dec) => {
                if (2..10).contains(&gap) {
                    for _ in 0..gap - 1 {
                        if let Ok(s) = dec.decode_float(&[], scratch, false) {
                            let mut guard = self.buffer.lock().unwrap();
                            for &v in &scratch[..s] {
                                let a = v.abs();
                                if a > self.peak {
                                    self.peak = a;
                                }
                            }
                            guard.push_frame(scratch[..s].iter().copied());
                        }
                    }
                }
                match dec.decode_float(payload, scratch, false) {
                    Ok(s) => s,
                    Err(_) => return,
                }
            }
            None => {
                let count = (payload.len() / 2).min(scratch.len());
                for (i, fr) in payload.chunks_exact(2).take(count).enumerate() {
                    scratch[i] = i16::from_le_bytes([fr[0], fr[1]]) as f32 / 32768.0;
                }
                count
            }
        };

        for &v in &scratch[..count] {
            let a = v.abs();
            if a > self.peak {
                self.peak = a;
            }
        }
        self.buffer
            .lock()
            .unwrap()
            .push_frame(scratch[..count].iter().copied());

        if self.last_emit.elapsed() >= Duration::from_millis(50) {
            let _ = self.app.emit("receiver-level", self.peak);
            self.peak = 0.0;
            self.last_emit = Instant::now();
        }
    }
}

/// Running receiver, stored in Tauri state. Dropping it stops the thread.
pub struct Receiver {
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
    _mdns: Option<ServiceDaemon>,
}

impl Receiver {
    pub fn start(
        app: AppHandle,
        device: Option<String>,
        port: u16,
        latency_ms: u32,
        pcm: bool,
        usb: bool,
        secure: bool,
    ) -> Result<Self, String> {
        // WiFi clients discover us via mDNS; USB clients connect through the tunnel.
        let mdns = if usb { None } else { advertise_mdns(port).ok() };

        // When pairing is required, generate a one-off key and show it as a QR
        // (and a link) the phone can use to encrypt the audio.
        let key: Option<[u8; 32]> = if secure {
            let key: [u8; 32] = rand::random();
            emit_pairing(&app, port, usb, &key);
            Some(key)
        } else {
            None
        };

        let stop = Arc::new(AtomicBool::new(false));
        let stop_thread = stop.clone();
        let thread = thread::Builder::new()
            .name("microhone-receiver".into())
            .spawn(move || {
                if let Err(e) = run(&app, device, port, latency_ms, pcm, usb, key, stop_thread) {
                    let _ = app.emit("receiver-error", e);
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(Self {
            stop,
            thread: Some(thread),
            _mdns: mdns,
        })
    }

    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for Receiver {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Best-effort primary LAN IPv4, found by opening a throwaway UDP socket.
fn local_ipv4() -> String {
    UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect("8.8.8.8:80")?;
            s.local_addr()
        })
        .map(|addr| addr.ip().to_string())
        .unwrap_or_else(|_| "127.0.0.1".to_string())
}

/// Emit the pairing payload (a `microhone://` link) and a QR SVG for the phone.
fn emit_pairing(app: &AppHandle, port: u16, usb: bool, key: &[u8; 32]) {
    let host = if usb { "127.0.0.1".to_string() } else { local_ipv4() };
    let key_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(key);
    let link = format!("microhone://pair?h={host}&p={port}&k={key_b64}");

    let svg = qrcode::QrCode::new(link.as_bytes())
        .map(|code| {
            code.render::<qrcode::render::svg::Color>()
                .min_dimensions(220, 220)
                .quiet_zone(true)
                .dark_color(qrcode::render::svg::Color("#0b1220"))
                .light_color(qrcode::render::svg::Color("#ffffff"))
                .build()
        })
        .unwrap_or_default();

    let _ = app.emit("pairing", serde_json::json!({ "link": link, "svg": svg }));
}

fn advertise_mdns(port: u16) -> Result<ServiceDaemon, String> {
    let mdns = ServiceDaemon::new().map_err(|e| e.to_string())?;
    let host = gethostname::gethostname().to_string_lossy().to_string();
    let safe: String = host
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect();
    let instance = format!("microhone on {host}");
    let host_name = format!("microhone-{safe}.local.");
    let props = [("v", "1"), ("proto", "udp")];
    let info = ServiceInfo::new(SERVICE_TYPE, &instance, &host_name, "", port, &props[..])
        .map_err(|e| e.to_string())?
        .enable_addr_auto();
    mdns.register(info).map_err(|e| e.to_string())?;
    Ok(mdns)
}

fn run(
    app: &AppHandle,
    device_name: Option<String>,
    port: u16,
    latency_ms: u32,
    pcm: bool,
    usb: bool,
    key: Option<[u8; 32]>,
    stop: Arc<AtomicBool>,
) -> Result<(), String> {
    let host = cpal::default_host();
    let device = match device_name {
        Some(substr) if !substr.is_empty() => {
            let needle = substr.to_lowercase();
            host.output_devices()
                .map_err(|e| e.to_string())?
                .find(|d| {
                    d.name()
                        .map(|n| n.to_lowercase().contains(&needle))
                        .unwrap_or(false)
                })
                .ok_or_else(|| format!("no output device matching '{substr}'"))?
        }
        _ => host
            .default_output_device()
            .ok_or("no default output device")?,
    };

    let supported = device.default_output_config().map_err(|e| e.to_string())?;
    let channels = supported.channels() as usize;
    let sample_rate = supported.sample_rate().0;
    let sample_format = supported.sample_format();
    let target = (sample_rate as u64 * latency_ms as u64 / 1000).max(1) as usize;
    let buffer = Arc::new(Mutex::new(JitterBuffer::new(target)));

    let config: cpal::StreamConfig = supported.into();
    let stream = match sample_format {
        cpal::SampleFormat::F32 => build_stream::<f32>(&device, &config, buffer.clone(), channels),
        cpal::SampleFormat::I16 => build_stream::<i16>(&device, &config, buffer.clone(), channels),
        cpal::SampleFormat::U16 => build_stream::<u16>(&device, &config, buffer.clone(), channels),
        other => Err(format!("unsupported output sample format: {other:?}")),
    }?;
    stream.play().map_err(|e| e.to_string())?;

    let codec = if pcm { "PCM" } else { "Opus" };
    let link = if usb { "USB" } else { "WiFi" };
    let _ = app.emit(
        "receiver-status",
        format!(
            "{link} · {codec} · out {} @ {sample_rate} Hz",
            device.name().unwrap_or_else(|_| "?".into())
        ),
    );

    let mut pipeline = Pipeline::new(app.clone(), buffer.clone(), pcm)?;
    let mut scratch = vec![0f32; 5760];
    let cipher = key.map(|k| Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&k)));

    if usb {
        run_tcp(app, port, &mut pipeline, &mut scratch, cipher.as_ref(), &stop)?;
    } else {
        run_udp(port, &mut pipeline, &mut scratch, cipher.as_ref(), &stop)?;
    }

    let _ = app.emit("receiver-level", 0.0f32);
    Ok(())
}

/// Decrypt (when paired) then hand the plaintext frame to the pipeline. A packet
/// that fails authentication is silently dropped, so only paired senders are heard.
fn feed(
    cipher: Option<&Aes256Gcm>,
    raw: &[u8],
    pipeline: &mut Pipeline,
    scratch: &mut [f32],
) {
    match cipher {
        Some(c) => {
            if raw.len() <= NONCE_LEN {
                return;
            }
            let (nonce, ct) = raw.split_at(NONCE_LEN);
            if let Ok(plain) = c.decrypt(Nonce::from_slice(nonce), ct) {
                pipeline.process(&plain, scratch);
            }
        }
        None => pipeline.process(raw, scratch),
    }
}

fn run_udp(
    port: u16,
    pipeline: &mut Pipeline,
    scratch: &mut [f32],
    cipher: Option<&Aes256Gcm>,
    stop: &AtomicBool,
) -> Result<(), String> {
    let socket = UdpSocket::bind(("0.0.0.0", port)).map_err(|e| e.to_string())?;
    socket
        .set_read_timeout(Some(Duration::from_millis(200)))
        .ok();
    let mut packet = [0u8; MAX_FRAME];
    while !stop.load(Ordering::Relaxed) {
        match socket.recv_from(&mut packet) {
            Ok((n, _)) => feed(cipher, &packet[..n], pipeline, scratch),
            Err(ref e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::TimedOut => {
                continue
            }
            Err(e) => return Err(e.to_string()),
        }
    }
    Ok(())
}

fn run_tcp(
    app: &AppHandle,
    port: u16,
    pipeline: &mut Pipeline,
    scratch: &mut [f32],
    cipher: Option<&Aes256Gcm>,
    stop: &AtomicBool,
) -> Result<(), String> {
    // Tunnel the phone's localhost:port to ours via adb, so it can connect over USB.
    adb_reverse(port)?;

    let listener = TcpListener::bind(("127.0.0.1", port)).map_err(|e| e.to_string())?;
    listener.set_nonblocking(true).map_err(|e| e.to_string())?;
    let _ = app.emit(
        "receiver-status",
        format!("USB · waiting for phone on tcp {port} (adb reverse) ..."),
    );

    let mut stream = loop {
        if stop.load(Ordering::Relaxed) {
            return Ok(());
        }
        match listener.accept() {
            Ok((s, _)) => break s,
            Err(ref e) if e.kind() == ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(100));
            }
            Err(e) => return Err(e.to_string()),
        }
    };
    stream.set_nonblocking(false).map_err(|e| e.to_string())?;
    stream
        .set_read_timeout(Some(Duration::from_millis(200)))
        .ok();
    let _ = app.emit("receiver-status", "USB · phone connected".to_string());

    let mut header = [0u8; 2];
    let mut frame = [0u8; MAX_FRAME];
    while !stop.load(Ordering::Relaxed) {
        if !read_full(&mut stream, &mut header, stop).map_err(|e| e.to_string())? {
            break;
        }
        let len = u16::from_be_bytes(header) as usize;
        if len == 0 || len > frame.len() {
            return Err("framing desync on USB stream".to_string());
        }
        if !read_full(&mut stream, &mut frame[..len], stop).map_err(|e| e.to_string())? {
            break;
        }
        feed(cipher, &frame[..len], pipeline, scratch);
    }
    Ok(())
}

/// Read exactly `buf.len()` bytes, tolerating read timeouts so we can still
/// notice `stop`. Returns `false` if the stream closed or stop was requested.
fn read_full<R: Read>(reader: &mut R, buf: &mut [u8], stop: &AtomicBool) -> std::io::Result<bool> {
    let mut filled = 0;
    while filled < buf.len() {
        if stop.load(Ordering::Relaxed) {
            return Ok(false);
        }
        match reader.read(&mut buf[filled..]) {
            Ok(0) => return Ok(false),
            Ok(n) => filled += n,
            Err(ref e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::TimedOut => {
                continue
            }
            Err(e) => return Err(e),
        }
    }
    Ok(true)
}

fn adb_reverse(port: u16) -> Result<(), String> {
    let adb = find_adb();
    let output = Command::new(&adb)
        .args(["reverse", &format!("tcp:{port}"), &format!("tcp:{port}")])
        .output()
        .map_err(|e| format!("could not run adb ({adb}); is USB debugging set up? {e}"))?;
    if !output.status.success() {
        return Err(format!(
            "adb reverse failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    Ok(())
}

/// Find `adb` on PATH, falling back to the default Android SDK location.
fn find_adb() -> String {
    if Command::new("adb").arg("version").output().is_ok() {
        return "adb".to_string();
    }
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        let path = Path::new(&local)
            .join("Android")
            .join("Sdk")
            .join("platform-tools")
            .join("adb.exe");
        if path.exists() {
            return path.to_string_lossy().into_owned();
        }
    }
    "adb".to_string()
}

fn build_stream<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    buffer: Arc<Mutex<JitterBuffer>>,
    channels: usize,
) -> Result<cpal::Stream, String>
where
    T: cpal::SizedSample + cpal::FromSample<f32>,
{
    device
        .build_output_stream(
            config,
            move |data: &mut [T], _: &cpal::OutputCallbackInfo| {
                let mut guard = buffer.lock().unwrap();
                for frame in data.chunks_mut(channels) {
                    let value = T::from_sample(guard.pop());
                    for out in frame.iter_mut() {
                        *out = value;
                    }
                }
            },
            |e| eprintln!("stream error: {e}"),
            None,
        )
        .map_err(|e| e.to_string())
}
