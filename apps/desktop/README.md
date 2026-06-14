# @microhone/desktop

Tauri 2 desktop host (Rust core + React/Vite/Tailwind UI).

## Prerequisites

- **Rust** toolchain (stable) — https://rustup.rs
- **Tauri 2 system deps** — https://v2.tauri.app/start/prerequisites/
  - Windows: Microsoft C++ Build Tools + WebView2 (usually preinstalled on Win 11)

## Develop

```bash
pnpm install            # from repo root
pnpm --filter @microhone/desktop tauri:dev
```

## Generate app icons (one-time)

`src-tauri/icons/` is not committed. Generate them from a square PNG:

```bash
pnpm --filter @microhone/desktop tauri icon path/to/logo.png
```

## Build

```bash
pnpm --filter @microhone/desktop tauri:build
```

## Status

The Rust core now hosts the audio receiver (UDP + Opus/PCM decode with PLC +
jitter buffer + cpal output) and advertises `_microhone._tcp` over mDNS, exposed
to the React UI via the `list_output_devices` / `start_receiver` / `stop_receiver`
commands and `receiver-level` / `receiver-status` events. Pick an output device
(e.g. VB-CABLE's "CABLE Input"), press Start, and stream from the phone.

> Building the Rust side needs **CMake** on `PATH` (libopus is built from source):
> `winget install -e --id Kitware.CMake`, then a fresh terminal.
