//! Audio receiver core for the desktop host.
//!
//! Ported from the `tools/audio-poc` CLI: receives the UDP audio channel
//! (`packages/protocol/PROTOCOL.md`), Opus-decodes with PLC (or raw PCM),
//! runs a target-latency jitter buffer, and plays into a chosen output device
//! via cpal. It also advertises `_microhone._tcp` over mDNS.
//!
//! Everything that touches a cpal `Stream` (which is `!Send` on Windows) lives
//! inside one dedicated thread, so the handle stored in Tauri state only carries
//! `Send` pieces (a stop flag, the join handle, the mDNS daemon).

use std::collections::VecDeque;
use std::io::ErrorKind;
use std::net::UdpSocket;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use magnum_opus::{Channels, Decoder};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use tauri::{AppHandle, Emitter};

const HEADER_LEN: usize = 8;
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
    ) -> Result<Self, String> {
        let mdns = advertise_mdns(port).ok();
        let stop = Arc::new(AtomicBool::new(false));
        let stop_thread = stop.clone();
        let thread = thread::Builder::new()
            .name("microhone-receiver".into())
            .spawn(move || {
                if let Err(e) = run(&app, device, port, latency_ms, pcm, stop_thread) {
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
    let _ = app.emit(
        "receiver-status",
        format!(
            "Listening on UDP {port} · {codec} · out {} @ {sample_rate} Hz",
            device.name().unwrap_or_else(|_| "?".into())
        ),
    );

    let mut decoder = if pcm {
        None
    } else {
        Some(Decoder::new(48_000, Channels::Mono).map_err(|e| e.to_string())?)
    };
    let mut decode_buf = vec![0f32; 5760];

    let socket = UdpSocket::bind(("0.0.0.0", port)).map_err(|e| e.to_string())?;
    socket
        .set_read_timeout(Some(Duration::from_millis(200)))
        .ok();

    let mut packet = [0u8; 4096];
    let mut last_seq: Option<u32> = None;
    let mut peak = 0f32;
    let mut last_emit = Instant::now();

    while !stop.load(Ordering::Relaxed) {
        let n = match socket.recv_from(&mut packet) {
            Ok((n, _)) => n,
            Err(ref e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::TimedOut => {
                continue
            }
            Err(e) => return Err(e.to_string()),
        };
        if n <= HEADER_LEN {
            continue;
        }

        let seq = u32::from_be_bytes([packet[0], packet[1], packet[2], packet[3]]);
        let gap = last_seq.map(|prev| seq.wrapping_sub(prev)).unwrap_or(1);
        last_seq = Some(seq);
        let payload = &packet[HEADER_LEN..n];

        let samples: &[f32] = match decoder.as_mut() {
            Some(dec) => {
                if (2..10).contains(&gap) {
                    for _ in 0..gap - 1 {
                        if let Ok(s) = dec.decode_float(&[], &mut decode_buf, false) {
                            let mut guard = buffer.lock().unwrap();
                            for &v in &decode_buf[..s] {
                                let a = v.abs();
                                if a > peak {
                                    peak = a;
                                }
                            }
                            guard.push_frame(decode_buf[..s].iter().copied());
                        }
                    }
                }
                match dec.decode_float(payload, &mut decode_buf, false) {
                    Ok(s) => &decode_buf[..s],
                    Err(_) => continue,
                }
            }
            None => {
                let count = (payload.len() / 2).min(decode_buf.len());
                for (i, fr) in payload.chunks_exact(2).take(count).enumerate() {
                    decode_buf[i] = i16::from_le_bytes([fr[0], fr[1]]) as f32 / 32768.0;
                }
                &decode_buf[..count]
            }
        };

        for &v in samples {
            let a = v.abs();
            if a > peak {
                peak = a;
            }
        }
        buffer.lock().unwrap().push_frame(samples.iter().copied());

        if last_emit.elapsed() >= Duration::from_millis(50) {
            let _ = app.emit("receiver-level", peak);
            peak = 0.0;
            last_emit = Instant::now();
        }
    }

    let _ = app.emit("receiver-level", 0.0f32);
    Ok(())
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
