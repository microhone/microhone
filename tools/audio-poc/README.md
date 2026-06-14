# microhone-audio-poc

Faz 1 proof-of-concept **receiver**. Listens on a UDP port for raw PCM audio
frames (see `packages/protocol/PROTOCOL.md`) and plays them on the default
output device via cpal.

This is throwaway scaffolding to prove the pipeline (Android capture → UDP →
desktop playback). The real receive/decode/jitter-buffer logic later lives in
`apps/desktop/src-tauri`.

## Build requirement

Opus (`magnum-opus`) builds libopus from source, which needs **CMake** and a C
compiler on `PATH`:

```powershell
winget install -e --id Kitware.CMake
```

Then open a fresh terminal so `cmake` is on `PATH`. (MSVC from the Build Tools is
already used for the C compiler.) If you only ever use `--pcm`, CMake is still
required because the crate is compiled either way.

## Run

```bash
cd tools/audio-poc
cargo run                            # default speakers, UDP 47801
cargo run -- --list                  # list output devices
cargo run -- --device "CABLE Input"  # route into a virtual device (faz 2)
cargo run -- --device "CABLE Input" --port 50000
cargo run -- --no-mdns               # don't advertise on the network
```

On start the receiver advertises itself as `_microhone._tcp` over mDNS, so the
Android app lists it automatically — no need to type the IP. Pick it in the app
(or enter the LAN IP + port manually) and press Start.

- **Faz 1:** default device → you hear your phone's mic from the PC speakers.
- **Faz 2:** route into VB-CABLE's "CABLE Input"; then pick "CABLE Output (VB-Audio
  Virtual Cable)" as the microphone in Discord/OBS. Set the CABLE device's default
  format to 48000 Hz so the pitch is correct.

## Notes

- No codec (raw `pcm_s16le`), no encryption, no pairing — Faz 1 only.
- Output is assumed 48 kHz. If your default device runs at another rate the
  pitch will be off until resampling is added in a later phase.
