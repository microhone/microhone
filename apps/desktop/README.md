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

> Faz 0 status: UI + Rust skeleton only. Audio/network/virtual-device logic
> lands in later phases (see `microhone-plan.md`, sections 5–10).
