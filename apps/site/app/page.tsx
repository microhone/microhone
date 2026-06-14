import type { ReactNode } from "react";
import { Reveal } from "./components/reveal";
import { HeroVisual } from "./components/hero-visual";

// Public "releases" repo that CI publishes artifacts to (code repo stays private).
const RELEASES_REPO = "https://github.com/microhone/microhone";
const GITHUB_URL = RELEASES_REPO;
const WINDOWS_DOWNLOAD = `${RELEASES_REPO}/releases/latest/download/microhone-windows-setup.exe`;
const ANDROID_DOWNLOAD = `${RELEASES_REPO}/releases/latest/download/microhone-android.apk`;

function BoltIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="size-5">
      <path
        d="M13 2 4 14h7l-1 8 10-12h-7l1-8z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function AppsIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="size-5">
      <rect x="3" y="3" width="7" height="7" rx="2" stroke="currentColor" strokeWidth="1.7" />
      <rect x="14" y="3" width="7" height="7" rx="2" stroke="currentColor" strokeWidth="1.7" />
      <rect x="3" y="14" width="7" height="7" rx="2" stroke="currentColor" strokeWidth="1.7" />
      <rect x="14" y="14" width="7" height="7" rx="2" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}

function PlugIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="size-5">
      <path
        d="M9 2v5M15 2v5M7 7h10v3a5 5 0 0 1-10 0zM12 15v7"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

const benefits: { icon: ReactNode; title: string; body: string }[] = [
  {
    icon: <BoltIcon />,
    title: "Barely any delay",
    body: "Fast enough for live calls and streaming, not just recording.",
  },
  {
    icon: <AppsIcon />,
    title: "Works with every app",
    body: "Discord, Zoom, OBS, Meet — if it uses a microphone, it just works.",
  },
  {
    icon: <PlugIcon />,
    title: "WiFi or USB",
    body: "Go fully wireless, or plug in a cable for the lowest delay.",
  },
];

export default function Home() {
  return (
    <div className="flex flex-1 flex-col">
      {/* Nav */}
      <header className="sticky top-0 z-20 border-b border-slate-100 bg-white/80 backdrop-blur-xl">
        <nav className="mx-auto flex w-full max-w-5xl items-center justify-between px-6 py-4">
          <span className="text-lg font-semibold tracking-tight">microhone</span>
          <a
            href="#download"
            className="rounded-full bg-blue-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-600"
          >
            Download
          </a>
        </nav>
      </header>

      {/* Hero */}
      <section className="relative overflow-hidden">
        <div
          aria-hidden
          className="animate-blob pointer-events-none absolute left-1/2 -top-32 -z-10 h-104 w-104 -translate-x-1/2 rounded-full bg-blue-300/30 blur-[120px]"
        />
        <div className="mx-auto flex w-full max-w-5xl flex-col items-center px-6 pb-10 pt-20 text-center sm:pt-28">
          <Reveal>
            <span className="rounded-full border border-blue-100 bg-blue-50 px-3 py-1 text-xs font-medium text-blue-600">
              Free &amp; open source
            </span>
          </Reveal>
          <Reveal delay={0.05}>
            <h1 className="mt-6 max-w-2xl text-5xl font-semibold leading-[1.05] tracking-tight text-slate-900 sm:text-6xl">
              Your phone is your PC&apos;s{" "}
              <span className="text-blue-500">mic</span>.
            </h1>
          </Reveal>
          <Reveal delay={0.1}>
            <p className="mx-auto mt-5 max-w-md text-lg text-slate-500">
              Speak into your phone and it comes out on your computer — in any app.
            </p>
          </Reveal>
          <Reveal delay={0.15}>
            <div className="mt-8 flex flex-col gap-3 sm:flex-row">
              <a
                href="#download"
                className="rounded-full bg-blue-500 px-6 py-3 font-medium text-white shadow-lg shadow-blue-500/25 transition-colors hover:bg-blue-600"
              >
                Download
              </a>
              <a
                href="#how"
                className="rounded-full border border-slate-200 px-6 py-3 font-medium text-slate-700 transition-colors hover:bg-slate-50"
              >
                How it works
              </a>
            </div>
          </Reveal>
          <Reveal delay={0.2} className="w-full">
            <HeroVisual />
          </Reveal>
        </div>
      </section>

      {/* Benefits */}
      <section id="how" className="mx-auto w-full max-w-5xl px-6 py-20">
        <div className="grid gap-5 sm:grid-cols-3">
          {benefits.map((b, i) => (
            <Reveal key={b.title} delay={i * 0.08}>
              <div className="h-full rounded-2xl border border-slate-200/80 bg-white p-6">
                <div className="flex size-11 items-center justify-center rounded-xl bg-blue-50 text-blue-600">
                  {b.icon}
                </div>
                <h3 className="mt-4 text-lg font-semibold text-slate-900">
                  {b.title}
                </h3>
                <p className="mt-2 text-sm leading-relaxed text-slate-500">
                  {b.body}
                </p>
              </div>
            </Reveal>
          ))}
        </div>
      </section>

      {/* Download */}
      <section id="download" className="mx-auto w-full max-w-5xl px-6 pb-24">
        <Reveal>
          <div className="rounded-3xl bg-blue-500 px-6 py-14 text-center text-white">
            <h2 className="text-3xl font-semibold tracking-tight sm:text-4xl">
              Get microhone
            </h2>
            <p className="mx-auto mt-3 max-w-sm text-blue-50">
              Install it on your computer and your phone. Free, no account.
            </p>
            <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
              <a
                href={WINDOWS_DOWNLOAD}
                className="rounded-full bg-white px-6 py-3 font-medium text-blue-600 transition-transform hover:scale-[1.03]"
              >
                Windows
              </a>
              <a
                href={ANDROID_DOWNLOAD}
                className="rounded-full border border-white/40 px-6 py-3 font-medium text-white transition-colors hover:bg-white/10"
              >
                Android
              </a>
            </div>
            <p className="mt-4 text-xs text-blue-100">macOS &amp; Linux soon.</p>
          </div>
        </Reveal>
      </section>

      {/* Footer */}
      <footer className="mt-auto border-t border-slate-100">
        <div className="mx-auto flex w-full max-w-5xl flex-col items-center justify-between gap-3 px-6 py-8 text-sm text-slate-400 sm:flex-row">
          <span className="font-semibold text-slate-600">microhone</span>
          <span>© {new Date().getFullYear()} microhone</span>
          <a href={GITHUB_URL} className="transition-colors hover:text-slate-600">
            GitHub
          </a>
        </div>
      </footer>
    </div>
  );
}
