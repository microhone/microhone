<<<<<<< HEAD
<p align="center">
  <img src="apps/desktop/src-tauri/icons/128x128.png" alt="microhone" width="92" />
</p>

<h1 align="center">microhone</h1>

<p align="center">
  Turn your phone into a microphone for your computer.<br />
  Over Wi-Fi or USB — into any app that reads a mic.
</p>

<p align="center">
  <a href="https://microhone.com"><strong>microhone.com</strong></a>
</p>

---

## Overview

Your phone already has a good microphone. microhone streams it to your computer
and feeds it into a **virtual audio device**, so anything that can read a
microphone — calls, streaming, recording — reads your phone instead.

Two apps do the work: an **Android client** that captures and streams the audio,
and a **desktop host** (Rust) that decodes it, absorbs the network, and plays it
into the virtual device.

The contract between them is written down **once**, in
[`packages/protocol/PROTOCOL.md`](./packages/protocol/PROTOCOL.md), and
implemented **twice** — in Kotlin and in Rust.

## Features

| Capability         | Description                                                                       |
| ------------------ | --------------------------------------------------------------------------------- |
| Wi-Fi mode         | Audio over UDP on the local network. The host is found automatically via mDNS.     |
| USB mode           | Audio over a TCP stream tunnelled through `adb`, for lower latency.                |
| Any app            | The audio lands in a virtual capture device, so every app on the machine sees it.  |
| QR pairing         | The desktop shows a `microhone://pair` QR; the phone scans it to receive the key.  |
| Encrypted audio    | Every frame is sealed with AES-256-GCM. Unauthenticated packets are dropped.       |
| Opus or raw PCM    | Opus with packet-loss concealment on Wi-Fi; raw `pcm_s16le` for a lossless path.   |
| Jitter buffer      | A target-latency buffer absorbs reordering and loss before playback.               |
| Background capture | An Android foreground service keeps the mic alive when the app isn't in view.      |

## Architecture

The audio takes one path, whatever the transport:

```
phone: capture → encode → encrypt
                    │
                    ▼   Wi-Fi (UDP)  ·  USB (TCP over adb)
                    │
  host: decrypt → decode (+PLC) → jitter buffer → virtual audio device → any app
```

| Piece                        | Role                                                                |
| ---------------------------- | ------------------------------------------------------------------- |
| `AudioEngine` / `AudioStreamer` | Captures the mic on Android and pushes frames onto the wire.      |
| `DeviceDiscovery` / `QrScanner`  | Finds hosts over mDNS; reads the pairing key from the QR.        |
| `MicForegroundService`       | Keeps capture running while the app is backgrounded.                |
| `receiver.rs`                | Decrypts, decodes, buffers, and plays out through `cpal`.           |
| `setup.rs`                   | First-run install of the virtual audio device (Windows).            |

### Wire protocol

Two channels, versioned by `PROTOCOL_VERSION`. Full spec in
[`PROTOCOL.md`](./packages/protocol/PROTOCOL.md).

| Channel | Wi-Fi | USB          | Carries                                            |
| ------- | ----- | ------------ | -------------------------------------------------- |
| Control | TCP   | TCP over adb | `HELLO` · `PAIR_REQ` · `CONFIG` · `START` / `STOP` |
| Audio   | UDP   | TCP over adb | `[seq][timestamp][payload]` frames                 |

Control is newline-delimited JSON. Defaults: control `47800`, audio `47801`,
advertised as `_microhone._tcp` over mDNS. Audio is 48 kHz mono in 10 ms frames.

### Pairing & encryption

The desktop generates a random 32-byte key and shows it as a QR / deep link:

```
microhone://pair?h=<host>&p=<port>&k=<base64url key>
```

Once paired, every audio packet on the wire becomes
`[ 12-byte nonce ][ AES-256-GCM ciphertext + tag ]`. Frames that fail
authentication are dropped, so only the paired phone is heard — and nobody else
on the network can listen in.

## The virtual microphone

This is the hard part of the project, and it is platform-specific.

**Windows.** A *real* device named "microhone" would need a signed kernel-mode
driver (EV certificate + Microsoft attestation), so for now the host leans on
**VB-CABLE**, a free virtual audio cable. On first run the app downloads and
installs it for you — one UAC prompt, logged to `%TEMP%\microhone\install.log`.
The device then appears as **CABLE Output**; select that as your microphone.
Shipping a signed driver under our own name is a later, paid step.

**macOS / Linux.** The paths are known — an AudioServerPlugin (à la BlackHole)
on macOS, a PipeWire/PulseAudio null sink on Linux — and are documented in
[`microhone-plan.md`](./microhone-plan.md) §5. The Windows host is what is wired
up today.

## Latency

| Use case                    | Budget  | Mode          |
| --------------------------- | ------- | ------------- |
| Calls, streaming, recording | ~100 ms | Wi-Fi is fine |
| Voice chat while gaming     | < 80 ms | Wi-Fi, tuned  |
| Monitoring yourself live    | < 30 ms | USB           |

## Tech stack

- **Desktop** — **Rust** · [**Tauri 2**](https://tauri.app) · React + Vite UI
- **Audio & network** — `cpal` (I/O) · `magnum-opus` (codec) · `aes-gcm`
  (encryption) · `mdns-sd` (discovery)
- **Mobile** — **Kotlin** · **Jetpack Compose** · Android foreground service
- **Site** — **Next.js** ([microhone.com](https://microhone.com))
- **Monorepo** — pnpm workspaces + Turborepo

## Repo layout

```
microhone/
├── apps/
│   ├── site/        # Next.js — microhone.com
│   ├── desktop/     # Tauri 2 host (Rust core + React/Vite UI)
│   └── mobile/      # Android client (Kotlin + Jetpack Compose)
└── packages/
    └── protocol/    # PROTOCOL.md — the contract both sides implement
```
=======
# microhone

**Turn your phone into your computer's microphone.**

Speak into your phone and it comes out on your PC — in Discord, Zoom, OBS and
any app. Over WiFi or USB. Free, no account.

[microhone.com](https://microhone.com) · [Download](https://github.com/microhone/microhone/releases/latest)

## Download
>>>>>>> 53a1d27fdf5267adc641d5c8ffc7283e40ede180

- **Windows** — [microhone-windows-setup.exe](https://github.com/microhone/microhone/releases/latest/download/microhone-windows-setup.exe)
- **Android** — [microhone-android.apk](https://github.com/microhone/microhone/releases/latest/download/microhone-android.apk)

<<<<<<< HEAD
## Getting started

Requires **Node 20+** with **pnpm** (via corepack). The desktop host also needs
**Rust** (stable) + Tauri system deps; the Android client needs **Android
Studio** / JDK 17 + the Android SDK.

```bash
corepack pnpm install

corepack pnpm site:dev       # landing site
corepack pnpm desktop:dev    # Tauri host (needs Rust)
```

Open `apps/mobile` in Android Studio for the phone client.

## Commits

Conventional Commits with a detailed body — see
[`microhone-plan.md`](./microhone-plan.md) §16.
=======
macOS and Linux coming soon.

## How it works

1. **Install** the desktop app on your computer and the microhone app on your phone.
2. **Connect** — your phone finds the PC on the network automatically, or plug in
   over USB for the lowest delay. Pair securely by scanning a QR code.
3. **Talk** — pick *microhone* as your microphone in any app.

## Features

- Low delay — good enough for live calls and streaming, not just recording
- Works with every app that reads a microphone (Discord, Zoom, OBS, Meet, …)
- WiFi or USB
- Encrypted, paired connection — only your phone can connect
- Free, no account, no telemetry

## Notes

- On Windows, microhone routes audio through the free
  [VB-CABLE](https://vb-audio.com/Cable/) virtual device — the app walks you
  through installing it on first run.
- The Windows installer is currently unsigned, so SmartScreen may warn on first
  run: choose **More info → Run anyway**.
>>>>>>> 53a1d27fdf5267adc641d5c8ffc7283e40ede180
