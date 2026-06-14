import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

function App() {
  const [devices, setDevices] = useState<string[]>([]);
  const [device, setDevice] = useState("");
  const [port, setPort] = useState("47801");
  const [latency, setLatency] = useState("40");
  const [pcm, setPcm] = useState(false);
  const [usb, setUsb] = useState(false);
  const [secure, setSecure] = useState(false);
  const [pairing, setPairing] = useState<{ link: string; svg: string } | null>(
    null,
  );
  const [running, setRunning] = useState(false);
  const [status, setStatus] = useState("Idle");
  const [level, setLevel] = useState(0);

  useEffect(() => {
    invoke<string[]>("list_output_devices").then(setDevices).catch(() => {});

    const subs: Promise<UnlistenFn>[] = [
      listen<number>("receiver-level", (e) => setLevel(e.payload)),
      listen<string>("receiver-status", (e) => setStatus(e.payload)),
      listen<{ link: string; svg: string }>("pairing", (e) =>
        setPairing(e.payload),
      ),
      listen<string>("receiver-error", (e) => {
        setStatus(`Error: ${e.payload}`);
        setRunning(false);
        setLevel(0);
      }),
    ];
    return () => {
      subs.forEach((p) => p.then((un) => un()));
    };
  }, []);

  async function start() {
    try {
      setPairing(null);
      await invoke("start_receiver", {
        device: device || null,
        port: parseInt(port, 10),
        latencyMs: parseInt(latency, 10),
        pcm,
        usb,
        secure,
      });
      setRunning(true);
    } catch (e) {
      setStatus(`Error: ${String(e)}`);
    }
  }

  async function stop() {
    await invoke("stop_receiver");
    setRunning(false);
    setStatus("Idle");
    setLevel(0);
    setPairing(null);
  }

  return (
    <main className="flex h-full flex-col gap-5 bg-black p-6 text-white">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-tight">🎙️ microhone</h1>
        <span className="font-mono text-xs text-white/40">desktop host</span>
      </header>

      <label className="flex flex-col gap-1 text-sm">
        <span className="text-white/60">Output device</span>
        <select
          value={device}
          onChange={(e) => setDevice(e.target.value)}
          disabled={running}
          className="rounded-md border border-white/15 bg-white/5 px-3 py-2 disabled:opacity-50"
        >
          <option value="">Default output</option>
          {devices.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
        <span className="text-xs text-white/40">
          Pick “CABLE Input” to feed Discord/OBS via VB-CABLE.
        </span>
      </label>

      <div className="flex gap-3">
        <label className="flex flex-1 flex-col gap-1 text-sm">
          <span className="text-white/60">Port</span>
          <input
            value={port}
            onChange={(e) => setPort(e.target.value)}
            disabled={running}
            className="rounded-md border border-white/15 bg-white/5 px-3 py-2 disabled:opacity-50"
          />
        </label>
        <label className="flex flex-1 flex-col gap-1 text-sm">
          <span className="text-white/60">Latency (ms)</span>
          <input
            value={latency}
            onChange={(e) => setLatency(e.target.value)}
            disabled={running}
            className="rounded-md border border-white/15 bg-white/5 px-3 py-2 disabled:opacity-50"
          />
        </label>
      </div>

      <label className="flex items-center gap-2 text-sm text-white/60">
        <input
          type="checkbox"
          checked={pcm}
          onChange={(e) => setPcm(e.target.checked)}
          disabled={running}
        />
        Raw PCM (instead of Opus)
      </label>

      <label className="flex items-center gap-2 text-sm text-white/60">
        <input
          type="checkbox"
          checked={usb}
          onChange={(e) => setUsb(e.target.checked)}
          disabled={running}
        />
        USB cable (adb reverse · low latency)
      </label>

      <label className="flex items-center gap-2 text-sm text-white/60">
        <input
          type="checkbox"
          checked={secure}
          onChange={(e) => setSecure(e.target.checked)}
          disabled={running}
        />
        Require pairing (encrypt the audio)
      </label>

      {pairing && (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-white/10 bg-white/5 p-4">
          <span className="text-sm text-white/70">Scan to pair your phone</span>
          <div
            className="overflow-hidden rounded-md bg-white p-2"
            dangerouslySetInnerHTML={{ __html: pairing.svg }}
          />
          <span className="break-all text-center font-mono text-[10px] text-white/40">
            {pairing.link}
          </span>
        </div>
      )}

      <div className="h-3 w-full overflow-hidden rounded-full bg-white/10">
        <div
          className="h-full bg-emerald-400 transition-[width] duration-75"
          style={{ width: `${Math.min(100, Math.round(level * 100))}%` }}
        />
      </div>

      <button
        onClick={running ? stop : start}
        className={`rounded-md px-4 py-2 font-medium transition-colors ${
          running
            ? "bg-red-500/90 hover:bg-red-500"
            : "bg-emerald-500/90 hover:bg-emerald-500"
        }`}
      >
        {running ? "Stop" : "Start"}
      </button>

      <p className="font-mono text-xs text-white/40">{status}</p>
    </main>
  );
}

export default App;
