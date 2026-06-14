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

use std::collections::VecDeque;
use std::net::UdpSocket;
use std::sync::{Arc, Mutex};

use anyhow::{anyhow, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};

/// Header is seq (u32) + timestamp (u32).
const HEADER_LEN: usize = 8;
/// Stream sample rate the Android client captures at.
const STREAM_SAMPLE_RATE: u32 = 48_000;
/// Cap the playback buffer (~1s @ 48k) so latency can't run away if the
/// network bursts. On overflow we drop the backlog and resync.
const MAX_BUFFER_SAMPLES: usize = STREAM_SAMPLE_RATE as usize;

type SampleBuffer = Arc<Mutex<VecDeque<f32>>>;

struct Args {
    port: u16,
    device: Option<String>,
    list: bool,
}

fn parse_args() -> Args {
    let mut port = 47801u16;
    let mut device = None;
    let mut list = false;
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--list" | "-l" => list = true,
            "--device" | "-d" => device = it.next(),
            "--port" | "-p" => {
                if let Some(v) = it.next() {
                    if let Ok(p) = v.parse() {
                        port = p;
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
    Args { port, device, list }
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

    let buffer: SampleBuffer = Arc::new(Mutex::new(VecDeque::new()));

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

    let config: cpal::StreamConfig = supported.into();
    let stream = match sample_format {
        cpal::SampleFormat::F32 => build_stream::<f32>(&device, &config, buffer.clone(), channels)?,
        cpal::SampleFormat::I16 => build_stream::<i16>(&device, &config, buffer.clone(), channels)?,
        cpal::SampleFormat::U16 => build_stream::<u16>(&device, &config, buffer.clone(), channels)?,
        other => return Err(anyhow!("unsupported output sample format: {other:?}")),
    };
    stream.play()?;

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
        if let Some(prev) = last_seq {
            let gap = seq.wrapping_sub(prev);
            if gap > 1 {
                lost += (gap - 1) as u64;
                if lost % 50 < (gap - 1) as u64 {
                    eprintln!("packet loss: ~{lost} dropped so far");
                }
            }
        }
        last_seq = Some(seq);

        let payload = &packet[HEADER_LEN..n];
        let mut guard = buffer.lock().unwrap();
        if guard.len() > MAX_BUFFER_SAMPLES {
            guard.clear(); // resync on overflow
        }
        for frame in payload.chunks_exact(2) {
            let s = i16::from_le_bytes([frame[0], frame[1]]);
            guard.push_back(s as f32 / 32768.0);
        }
    }
}

/// Build an output stream that drains the shared mono buffer, fanning each
/// mono sample out to every output channel.
fn build_stream<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    buffer: SampleBuffer,
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
                let sample = guard.pop_front().unwrap_or(0.0);
                let value = T::from_sample(sample);
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
