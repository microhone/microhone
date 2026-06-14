# microhone wire protocol

**Spec version:** `1` (draft)

This is the single source of truth for how the Android client and the desktop
host talk to each other. Both sides (Kotlin + Rust) MUST implement against this
document. Bump `PROTOCOL_VERSION` on any breaking change and keep older versions
parseable where practical (forward compatibility).

```
PROTOCOL_VERSION = 1
```

There are two channels:

| Channel | Transport (WiFi) | Transport (USB) | Purpose |
|---|---|---|---|
| Control | TCP | TCP (over `adb forward`) | handshake, pairing, config, start/stop, keepalive |
| Audio   | UDP | TCP (over `adb forward`) | real-time audio frames |

Default ports (advertised via mDNS, overridable): control `47800`, audio `47801`.

---

## 1. Control channel (TCP)

Newline-delimited JSON (one JSON object per line, `\n` terminated). Small and
debuggable for now; may move to MessagePack later without changing semantics.

Every message has a `type` field. Messages:

### `HELLO` (client → host)
Client introduces itself.
```json
{ "type": "HELLO", "protocol": 1, "device": "Pixel 8", "app": "0.0.0", "caps": ["opus", "pcm"] }
```

### `HELLO_ACK` (host → client)
```json
{ "type": "HELLO_ACK", "protocol": 1, "host": "DESKTOP-PC", "needsPairing": true }
```

### `PAIR_REQ` (client → host)
Proves the client scanned the QR / typed the PIN.
```json
{ "type": "PAIR_REQ", "token": "<from QR>", "pin": "123456" }
```

### `PAIR_RESULT` (host → client)
```json
{ "type": "PAIR_RESULT", "ok": true, "sessionId": "uuid", "reason": null }
```

### `CONFIG` (host → client)
Negotiated audio settings the client must use before streaming.
```json
{ "type": "CONFIG", "sampleRate": 48000, "channels": 1, "codec": "pcm_s16le", "frameMs": 10, "audioPort": 47801 }
```
- `codec`: `"pcm_s16le"` (PoC default) or `"opus"`.
- `frameMs`: audio frame duration; 10 ms recommended.

### `START` / `STOP` (client ↔ host)
```json
{ "type": "START" }
{ "type": "STOP" }
```

### `PING` / `PONG` (both ways, keepalive)
```json
{ "type": "PING", "t": 1234567890 }
{ "type": "PONG", "t": 1234567890 }
```

---

## 2. Audio channel

Binary frames. Each packet:

```
 0               1               2               3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          seq (u32, BE)                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       timestamp (u32, BE)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     payload (codec bytes ...)                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- `seq`: monotonically increasing per session. Used for ordering and loss
  detection.
- `timestamp`: sample-clock timestamp (in samples or ms — fixed at `frameMs`
  granularity) for jitter-buffer alignment.
- `payload`:
  - `pcm_s16le`: raw 16-bit little-endian samples (mono), `sampleRate*frameMs/1000`
    samples per packet.
  - `opus`: one Opus packet per frame.

On UDP, packets may arrive out of order, duplicated, or be lost — the host's
jitter buffer + Opus PLC handle this. On TCP (USB mode) ordering is guaranteed,
so the jitter buffer can be smaller.

---

## 3. Security — pairing & encryption

Pairing is optional. When enabled, the desktop generates a random 32-byte key
and shows it as a QR / link:

```
microhone://pair?h=<host>&p=<port>&k=<base64url 32-byte key>
```

The phone reads it (scan or paste) and then **encrypts every audio frame** with
**AES-256-GCM**. The plaintext is the normal frame `[seq][timestamp][payload]`;
the wire packet becomes:

```
[ nonce: 12 bytes ][ AES-256-GCM ciphertext + 16-byte tag ]
```

- A fresh random 96-bit `nonce` per packet is prepended (it need not be secret).
- The desktop decrypts with the key; packets that fail authentication are
  dropped, so only a paired sender is heard (and the audio is confidential on
  the LAN). On TCP/USB the length prefix covers the whole encrypted packet.

This is the MVP. A future step may add a Noise-style handshake and remembered
devices so pairing is a one-time step. See `microhone-plan.md` §7.
