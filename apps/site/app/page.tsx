export default function Home() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-6 bg-black px-6 text-center">
      <span className="rounded-full border border-white/15 px-3 py-1 font-mono text-xs uppercase tracking-widest text-white/60">
        microhone.com
      </span>
      <h1 className="bg-linear-to-b from-white to-white/50 bg-clip-text text-5xl font-semibold tracking-tight text-transparent sm:text-7xl">
        Your phone is the mic.
      </h1>
      <p className="max-w-xl text-balance text-lg text-white/60">
        Use your phone&apos;s microphone as a real microphone on your computer.
        WiFi &amp; USB, low latency, works with Discord, OBS and Zoom.
      </p>
      <p className="font-mono text-sm text-white/40">Coming soon.</p>
    </main>
  );
}
