//! microhone — Faz 1/2 audio PoC (receiver).
//!
//! Listens for the audio channel described in `packages/protocol/PROTOCOL.md`:
//! each UDP packet is `[ seq:u32 BE ][ timestamp:u32 BE ][ pcm_s16le mono ]`.
//! Incoming samples are pushed into a small ring buffer and played on an
//! output device with cpal. No codec, no jitter adaptation yet — this exists
//! only to prove capture -> transport -> playback end to end.
//!
//! Faz 2: pick a *virtual* output device (e.g. VB-CABLE's "CABLE Input") so the
//! audio shows up as a microphone (its "CABLE Output") in Discord/OBS.
//!
//! Usage:
//!   cargo run -- --list                     # list output devices
//!   cargo run                               # default speakers, port 47801
//!   cargo run -- --device "CABLE Input"     # route into VB-CABLE
//!   cargo run -- --device "CABLE Input" --port 50000
//!   cargo run -- --latency 60               # target jitter-buffer latency (ms)
//!   cargo run -- --pcm                      # raw PCM instead of Opus (faz 1 mode)
//!
//! Default codec is Opus (matches the Android app's default). Use --pcm on both
//! sides to fall back to raw PCM.

use std::collections::VecDeque;
use std::net::UdpSocket;
use std::sync::{Arc, Mutex};

use anyhow::{anyhow, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use magnum_opus::{Channels, Decoder};
use mdns_sd::{ServiceDaemon, ServiceInfo};

/// Header is seq (u32) + timestamp (u32).
const HEADER_LEN: usize = 8;
/// Stream sample rate the Android client captures at.
const STREAM_SAMPLE_RATE: u32 = 48_000;
/// Default target latency held in the jitter buffer.
const DEFAULT_LATENCY_MS: u32 = 40;
/// mDNS service type the Android app discovers.
const SERVICE_TYPE: &str = "_microhone._tcp.local.";

type SharedJitter = Arc<Mutex<JitterBuffer>>;

/// A minimal adaptive-ish jitter buffer.
///
/// It holds mono samples and aims to keep roughly `target` of them queued:
/// - **Priming:** after startup or an underrun it outputs silence until it has
///   buffered `target` samples, so playback starts with a cushion instead of
///   crackling.
/// - **Overflow:** if the queue grows past `max` (network burst / clock drift)
///   it drops the oldest samples back down to `target`, trading a tiny skip for
///   bounded latency instead of an ever-growing delay.
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

    /// Append a decoded frame, trimming back to `target` on overflow.
    fn push_frame<I: IntoIterator<Item = f32>>(&mut self, frame: I) {
        self.samples.extend(frame);
        if self.samples.len() > self.max {
            let overflow = self.samples.len() - self.target;
            self.samples.drain(0..overflow);
        }
    }

    /// Pull one sample for the output callback (silence while priming/underrun).
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
                self.priming = true; // re-prime after an underrun
                0.0
            }
        }
    }
}

struct Args {
    port: u16,
    device: Option<String>,
    list: bool,
    latency_ms: u32,
    pcm: bool,
    no_mdns: bool,
}

fn parse_args() -> Args {
    let mut port = 47801u16;
    let mut device = None;
    let mut list = false;
    let mut latency_ms = DEFAULT_LATENCY_MS;
    let mut pcm = false;
    let mut no_mdns = false;
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--list" | "-l" => list = true,
            "--pcm" => pcm = true,
            "--no-mdns" => no_mdns = true,
            "--device" | "-d" => device = it.next(),
            "--port" | "-p" => {
                if let Some(v) = it.next() {
                    if let Ok(p) = v.parse() {
                        port = p;
                    }
                }
            }
            "--latency" => {
                if let Some(v) = it.next() {
                    if let Ok(ms) = v.parse() {
                        latency_ms = ms;
                    }
                }
            }
            // bare number is treated as the port for convenience
            other => {
                if let Ok(p) = other.parse::<u16>() {
                    port = p;
                }
            }
        }
    }
    Args {
        port,
        device,
        list,
        latency_ms,
        pcm,
        no_mdns,
    }
}

/// Advertise this host as `_microhone._tcp` so the Android app can discover it
/// without typing an IP. Returns the daemon, which must stay alive to keep the
/// record published.
fn advertise_mdns(port: u16) -> Result<ServiceDaemon> {
    let mdns = ServiceDaemon::new()?;
    let host = gethostname::gethostname().to_string_lossy().to_string();
    let safe: String = host
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect();
    let instance = format!("microhone on {host}");
    let host_name = format!("microhone-{safe}.local.");
    let props = [("v", "1"), ("proto", "udp")];
    let info = ServiceInfo::new(SERVICE_TYPE, &instance, &host_name, "", port, &props[..])?
        .enable_addr_auto();
    mdns.register(info)?;
    println!("Advertising {SERVICE_TYPE} as \"{instance}\" on port {port}");
    Ok(mdns)
}

fn main() -> Result<()> {
    let args = parse_args();
    let host = cpal::default_host();

    if args.list {
        println!("Available output devices:");
        for (i, device) in host.output_devices()?.enumerate() {
            println!("  [{i}] {}", device.name().unwrap_or_else(|_| "<unknown>".into()));
        }
        println!("\nRoute into one with: --device \"<part of the name>\"");
        return Ok(());
    }

    // --- Output device (cpal) ---
    let device = match &args.device {
        Some(substr) => {
            let needle = substr.to_lowercase();
            host.output_devices()?
                .find(|d| {
                    d.name()
                        .map(|n| n.to_lowercase().contains(&needle))
                        .unwrap_or(false)
                })
                .ok_or_else(|| {
                    anyhow!("no output device matching '{substr}'; run with --list to see options")
                })?
        }
        None => host
            .default_output_device()
            .ok_or_else(|| anyhow!("no default output device found"))?,
    };

    let supported = device.default_output_config()?;
    let channels = supported.channels() as usize;
    let sample_rate = supported.sample_rate().0;
    let sample_format = supported.sample_format();

    println!(
        "Output device : {}\n  {} Hz, {} ch, {:?}",
        device.name().unwrap_or_else(|_| "<unknown>".into()),
        sample_rate,
        channels,
        sample_format,
    );
    if sample_rate != STREAM_SAMPLE_RATE {
        eprintln!(
            "warning: output runs at {sample_rate} Hz but the stream is {STREAM_SAMPLE_RATE} Hz; \
             pitch will be off. Set this device's default format to 48000 Hz (resampling lands later)."
        );
    }

    // Jitter buffer sized in samples at the *output* rate.
    let target_samples = (sample_rate as u64 * args.latency_ms as u64 / 1000) as usize;
    println!("Jitter buffer : ~{} ms target ({} samples)", args.latency_ms, target_samples);
    let buffer: SharedJitter = Arc::new(Mutex::new(JitterBuffer::new(target_samples.max(1))));

    let config: cpal::StreamConfig = supported.into();
    let stream = match sample_format {
        cpal::SampleFormat::F32 => build_stream::<f32>(&device, &config, buffer.clone(), channels)?,
        cpal::SampleFormat::I16 => build_stream::<i16>(&device, &config, buffer.clone(), channels)?,
        cpal::SampleFormat::U16 => build_stream::<u16>(&device, &config, buffer.clone(), channels)?,
        other => return Err(anyhow!("unsupported output sample format: {other:?}")),
    };
    stream.play()?;

    // --- Codec ---
    // Opus is always 48 kHz; max frame we ever decode is 120 ms mono.
    let mut decoder = if args.pcm {
        println!("Codec         : raw PCM");
        None
    } else {
        println!("Codec         : Opus (48 kHz mono) with PLC");
        Some(Decoder::new(48_000, Channels::Mono).map_err(|e| anyhow!("opus decoder: {e}"))?)
    };
    let mut decode_buf = vec![0f32; 5760];

    // --- mDNS advertisement (kept alive for the program's lifetime) ---
    let _mdns_guard = if args.no_mdns {
        None
    } else {
        match advertise_mdns(args.port) {
            Ok(daemon) => Some(daemon),
            Err(e) => {
                eprintln!("mDNS advertise failed ({e}); clients must enter the IP manually");
                None
            }
        }
    };

    // --- UDP receiver ---
    let socket = UdpSocket::bind(("0.0.0.0", args.port))?;
    println!(
        "Listening for audio on UDP 0.0.0.0:{} (Ctrl+C to stop) ...",
        args.port
    );

    let mut packet = [0u8; 4096];
    let mut last_seq: Option<u32> = None;
    let mut lost: u64 = 0;

    loop {
        let (n, _addr) = socket.recv_from(&mut packet)?;
        if n <= HEADER_LEN {
            continue;
        }

        let seq = u32::from_be_bytes([packet[0], packet[1], packet[2], packet[3]]);
        let gap = last_seq.map(|prev| seq.wrapping_sub(prev)).unwrap_or(1);
        if gap > 1 {
            lost += (gap - 1) as u64;
            if lost % 50 < (gap - 1) as u64 {
                eprintln!("packet loss: ~{lost} dropped so far");
            }
        }
        last_seq = Some(seq);

        let payload = &packet[HEADER_LEN..n];

        match decoder.as_mut() {
            // Opus: conceal small gaps with PLC, then decode the real packet.
            Some(dec) => {
                if (2..10).contains(&gap) {
                    for _ in 0..gap - 1 {
                        if let Ok(samples) = dec.decode_float(&[], &mut decode_buf, false) {
                            buffer
                                .lock()
                                .unwrap()
                                .push_frame(decode_buf[..samples].iter().copied());
                        }
                    }
                }
                match dec.decode_float(payload, &mut decode_buf, false) {
                    Ok(samples) => buffer
                        .lock()
                        .unwrap()
                        .push_frame(decode_buf[..samples].iter().copied()),
                    Err(e) => eprintln!("opus decode error: {e}"),
                }
            }
            // Raw PCM: little-endian i16 mono.
            None => {
                let samples = payload
                    .chunks_exact(2)
                    .map(|frame| i16::from_le_bytes([frame[0], frame[1]]) as f32 / 32768.0);
                buffer.lock().unwrap().push_frame(samples);
            }
        }
    }
}

/// Build an output stream that drains the shared mono buffer, fanning each
/// mono sample out to every output channel.
fn build_stream<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    buffer: SharedJitter,
    channels: usize,
) -> Result<cpal::Stream>
where
    T: cpal::SizedSample + cpal::FromSample<f32>,
{
    let err_fn = |e| eprintln!("stream error: {e}");
    let stream = device.build_output_stream(
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
        err_fn,
        None,
    )?;
    Ok(stream)
}
