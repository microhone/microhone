# 🎙️ microhone

Turn your phone's microphone into a real microphone on your computer — over WiFi
and USB. Works with Discord, OBS, Zoom and anything that reads a system mic.

> Full plan & technical spec: [`microhone-plan.md`](./microhone-plan.md)

## Monorepo layout

```
microhone/
├── apps/
│   ├── site/        # Next.js — microhone.com landing site
│   ├── desktop/     # Tauri 2 host (Rust core + React/Vite/Tailwind UI)
│   └── mobile/      # Android client (Kotlin + Jetpack Compose)
├── packages/
│   └── protocol/    # Wire protocol spec (Kotlin + Rust both implement it)
└── microhone-plan.md
```

`apps/site` and `apps/desktop` are pnpm workspace packages managed with
Turborepo. `apps/mobile` is a standalone Gradle project.

## Prerequisites

- **Node 20+** and **pnpm** (via `corepack pnpm …`, pinned in `package.json`)
- **Rust** (stable) + Tauri system deps — for `apps/desktop`
- **Android Studio** / JDK 17 + Android SDK — for `apps/mobile`

## Getting started

```bash
corepack pnpm install        # install JS deps for site + desktop

corepack pnpm site:dev       # run the landing site
corepack pnpm desktop:dev    # run the Tauri desktop UI (needs Rust)
```

For the Android app, open `apps/mobile` in Android Studio. Per-app setup lives
in each app's own `README.md`.

## Status

Faz 0 — monorepo skeleton. Each app compiles/scaffolds; audio, networking and
the virtual microphone land in later phases (see the plan's roadmap, §13).

## Commits

Conventional Commits with a detailed body — see `microhone-plan.md` §16.
