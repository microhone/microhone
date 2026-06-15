import type { ReactNode } from "react";
import { Reveal } from "./components/reveal";
import { HeroVisual } from "./components/hero-visual";
import { AppsViz, LatencyViz, LinkViz } from "./components/card-visuals";

// Public "releases" repo that CI publishes artifacts to (code repo stays private).
const RELEASES_REPO = "https://github.com/microhone/microhone";
const GITHUB_URL = RELEASES_REPO;
// Served through our own /download proxy so the download starts on microhone.com
// instead of navigating to github.com.
const WINDOWS_DOWNLOAD = "/download/windows";
const ANDROID_DOWNLOAD = "/download/android";

function LogoMark({ className = "size-7" }: { className?: string }) {
  return (
    <svg viewBox="0 0 1024 1024" className={className} aria-hidden>
      <defs>
        <linearGradient id="logo-grad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#3B82F6" />
          <stop offset="1" stopColor="#2563EB" />
        </linearGradient>
      </defs>
      <rect width="1024" height="1024" rx="232" fill="url(#logo-grad)" />
      <rect x="412" y="230" width="200" height="330" rx="100" fill="#fff" />
      <path
        d="M330 470 a182 182 0 0 0 364 0"
        fill="none"
        stroke="#fff"
        strokeWidth="46"
        strokeLinecap="round"
      />
      <rect x="490" y="648" width="44" height="118" rx="22" fill="#fff" />
      <rect x="392" y="760" width="240" height="46" rx="23" fill="#fff" />
    </svg>
  );
}

function DownloadIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="size-4">
      <path
        d="M12 3v12m0 0 4-4m-4 4-4-4M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

const benefits: { viz: ReactNode; title: string; body: string }[] = [
  {
    viz: <LatencyViz />,
    title: "Barely any delay",
    body: "Fast enough for live calls and streaming, not just recording.",
  },
  {
    viz: <AppsViz />,
    title: "Works with every app",
    body: "Discord, Zoom, OBS, Meet — if it uses a microphone, it just works.",
  },
  {
    viz: <LinkViz />,
    title: "WiFi or USB",
    body: "Go fully wireless, or plug in a cable for the lowest delay.",
  },
];

const primaryButton =
  "inline-flex items-center justify-center gap-2 rounded-full bg-blue-500 px-6 py-3 font-medium text-white shadow-lg shadow-blue-500/25 transition-all duration-200 hover:bg-blue-600 hover:shadow-xl hover:shadow-blue-500/30 active:scale-95";
const secondaryButton =
  "inline-flex items-center justify-center gap-2 rounded-full border border-slate-200 bg-white px-6 py-3 font-medium text-slate-700 transition-all duration-200 hover:border-slate-300 hover:bg-slate-50 active:scale-95";

export default function Home() {
  return (
    <div className="relative flex flex-1 flex-col">
      {/* Background */}
      <div
        aria-hidden
        className="pointer-events-none fixed inset-0 -z-10 overflow-hidden"
      >
        <div className="bg-dots absolute inset-0 mask-[radial-gradient(ellipse_70%_55%_at_50%_0%,black,transparent)]" />
        <div className="animate-blob absolute -top-24 left-[18%] h-96 w-96 rounded-full bg-blue-300/45 blur-[110px]" />
        <div className="animate-blob absolute top-1/4 right-[4%] h-80 w-80 rounded-full bg-sky-300/40 blur-[110px] [animation-delay:4s]" />
      </div>

      {/* Nav */}
      <header className="sticky top-0 z-20 border-b border-slate-100 bg-white/70 backdrop-blur-xl">
        <nav className="mx-auto flex w-full max-w-5xl items-center justify-between px-6 py-4">
          <span className="flex items-center gap-2">
            <LogoMark />
            <span className="text-lg font-semibold tracking-tight">microhone</span>
          </span>
          <a
            href="#download"
            className="rounded-full bg-blue-500 px-4 py-2 text-sm font-medium text-white shadow-sm shadow-blue-500/25 transition-all duration-200 hover:bg-blue-600 hover:shadow-md active:scale-95"
          >
            Download
          </a>
        </nav>
      </header>

      {/* Hero */}
      <section className="mx-auto flex w-full max-w-5xl flex-col items-center px-6 pb-20 pt-32 text-center sm:pt-44">
        <Reveal>
          <h1 className="max-w-2xl text-5xl font-semibold leading-[1.05] tracking-tight text-slate-900 sm:text-6xl">
            Your phone is your PC&apos;s{" "}
            <span className="bg-linear-to-r from-blue-500 to-sky-500 bg-clip-text text-transparent">
              mic
            </span>
            .
          </h1>
        </Reveal>
        <Reveal delay={0.07}>
          <p className="mx-auto mt-5 max-w-md text-lg text-slate-500">
            Speak into your phone and it comes out on your computer — in any app.
          </p>
        </Reveal>
        <Reveal delay={0.14}>
          <div className="mt-8 flex flex-col gap-3 sm:flex-row">
            <a href="#download" className={primaryButton}>
              <DownloadIcon />
              Download
            </a>
            <a href="#how" className={secondaryButton}>
              How it works
            </a>
          </div>
        </Reveal>
        <Reveal delay={0.2} className="w-full">
          <HeroVisual />
        </Reveal>
      </section>

      {/* Benefits */}
      <section id="how" className="mx-auto w-full max-w-5xl px-6 py-24">
        <div className="grid gap-8 sm:grid-cols-3">
          {benefits.map((b, i) => (
            <Reveal key={b.title} delay={i * 0.08}>
              <div className="group h-full rounded-2xl border border-slate-200 bg-white p-8 shadow-sm transition-all duration-300 hover:-translate-y-1.5 hover:border-blue-200 hover:shadow-xl hover:shadow-blue-500/10">
                <div className="flex h-28 items-center justify-center rounded-xl border border-blue-100/70 bg-blue-50/60">
                  {b.viz}
                </div>
                <h3 className="mt-6 text-xl font-semibold text-slate-900">
                  {b.title}
                </h3>
                <p className="mt-2.5 text-[15px] leading-relaxed text-slate-500">
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
          <div className="relative overflow-hidden rounded-3xl bg-linear-to-br from-blue-500 to-blue-600 px-6 py-16 text-center text-white shadow-xl shadow-blue-500/20">
            <div className="pointer-events-none absolute -top-16 left-1/2 h-64 w-64 -translate-x-1/2 rounded-full bg-white/15 blur-3xl" />
            <h2 className="relative text-3xl font-semibold tracking-tight sm:text-4xl">
              Get microhone
            </h2>
            <p className="relative mx-auto mt-3 max-w-sm text-blue-50">
              Install it on your computer and your phone. Free, no account.
            </p>
            <div className="relative mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
              <a
                href={WINDOWS_DOWNLOAD}
                className="inline-flex items-center justify-center gap-2 rounded-full bg-white px-6 py-3 font-medium text-blue-600 shadow-lg shadow-blue-900/10 transition-all duration-200 hover:bg-blue-50 hover:text-blue-700 hover:shadow-xl active:scale-95"
              >
                <DownloadIcon />
                Windows
              </a>
              <a
                href={ANDROID_DOWNLOAD}
                className="inline-flex items-center justify-center gap-2 rounded-full border border-white/50 px-6 py-3 font-medium text-white transition-all duration-200 hover:border-white hover:bg-white/15 active:scale-95"
              >
                <DownloadIcon />
                Android
              </a>
            </div>
            <p className="relative mt-4 text-xs text-blue-100">
              Windows 10/11 and Android 8+ · macOS &amp; Linux soon.
            </p>
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
