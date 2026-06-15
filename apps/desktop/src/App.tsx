import { useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { check, type Update } from "@tauri-apps/plugin-updater";
import { relaunch } from "@tauri-apps/plugin-process";

const SETTINGS_KEY = "microhone-settings";

type Settings = {
  device?: string;
  port?: string;
  latency?: string;
  pcm?: boolean;
  usb?: boolean;
  secure?: boolean;
};

const saved: Settings = (() => {
  try {
    return JSON.parse(localStorage.getItem(SETTINGS_KEY) || "{}");
  } catch {
    return {};
  }
})();

function Toggle({
  checked,
  onChange,
  disabled,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`relative h-6 w-10 shrink-0 rounded-full transition-colors disabled:opacity-50 ${
        checked ? "bg-blue-500" : "bg-slate-200"
      }`}
    >
      <span
        className={`absolute top-0.5 size-5 rounded-full bg-white shadow transition-all ${
          checked ? "left-4.5" : "left-0.5"
        }`}
      />
    </button>
  );
}

function SettingRow({
  label,
  hint,
  checked,
  onChange,
  disabled,
}: {
  label: string;
  hint: string;
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-4 py-2.5">
      <div>
        <div className="text-sm font-medium text-slate-700">{label}</div>
        <div className="text-xs text-slate-400">{hint}</div>
      </div>
      <Toggle checked={checked} onChange={onChange} disabled={disabled} />
    </div>
  );
}

function App() {
  const [devices, setDevices] = useState<string[]>([]);
  const [device, setDevice] = useState(saved.device ?? "");
  const [port, setPort] = useState(saved.port ?? "47801");
  const [latency, setLatency] = useState(saved.latency ?? "40");
  const [pcm, setPcm] = useState(saved.pcm ?? false);
  const [usb, setUsb] = useState(saved.usb ?? false);
  const [secure, setSecure] = useState(saved.secure ?? false);
  const [pairing, setPairing] = useState<{ link: string; svg: string } | null>(
    null,
  );
  const [running, setRunning] = useState(false);
  const [status, setStatus] = useState("Idle");
  const [level, setLevel] = useState(0);
  const [update, setUpdate] = useState<Update | null>(null);
  const [updating, setUpdating] = useState(false);

  // Check for a new version once on launch.
  useEffect(() => {
    check()
      .then((u) => {
        if (u) setUpdate(u);
      })
      .catch(() => {});
  }, []);

  async function installUpdate() {
    if (!update) return;
    setUpdating(true);
    try {
      await update.downloadAndInstall();
      await relaunch();
    } catch (e) {
      setStatus(`Update failed: ${String(e)}`);
      setUpdating(false);
    }
  }

  const autoSelected = useRef(false);
  const cableDevice =
    devices.find((d) => /cable input/i.test(d)) ??
    devices.find((d) => /cable/i.test(d));
  const vbInstalled = Boolean(cableDevice);

  function refreshDevices() {
    invoke<string[]>("list_output_devices").then(setDevices).catch(() => {});
  }

  async function installVbCable() {
    try {
      await invoke("install_vbcable");
      setStatus(
        "Approve the prompt, click “Install Driver”, then reboot and press “Recheck”.",
      );
    } catch (e) {
      setStatus(`Error: ${String(e)}`);
    }
  }

  // Auto-pick the virtual cable the first time we see it.
  useEffect(() => {
    if (!autoSelected.current && device === "" && cableDevice) {
      setDevice(cableDevice);
      autoSelected.current = true;
    }
  }, [cableDevice, device]);

  useEffect(() => {
    refreshDevices();

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

  // Remember the settings across runs.
  useEffect(() => {
    const settings: Settings = { device, port, latency, pcm, usb, secure };
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
  }, [device, port, latency, pcm, usb, secure]);

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

  const isError = status.startsWith("Error");
  const pillColor = isError
    ? "bg-red-50 text-red-600"
    : running
      ? "bg-blue-50 text-blue-600"
      : "bg-slate-100 text-slate-500";
  const dotColor = isError
    ? "bg-red-500"
    : running
      ? "bg-blue-500 animate-pulse"
      : "bg-slate-400";

  return (
    <main className="min-h-full bg-slate-50 text-slate-900">
      {update && (
        <div className="flex items-center justify-between gap-3 bg-blue-500 px-6 py-2 text-sm text-white">
          <span>microhone {update.version} is available</span>
          <button
            onClick={installUpdate}
            disabled={updating}
            className="rounded-full bg-white px-3 py-1 text-xs font-medium text-blue-600 transition-colors hover:bg-blue-50 disabled:opacity-60"
          >
            {updating ? "Installing…" : "Install & restart"}
          </button>
        </div>
      )}
      <div className="mx-auto flex max-w-md flex-col gap-5 px-6 py-8">
        <header className="flex items-center justify-between">
          <div>
            <h1 className="text-lg font-semibold tracking-tight">microhone</h1>
            <p className="text-xs text-slate-400">desktop host</p>
          </div>
          <span
            className={`flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium ${pillColor}`}
          >
            <span className={`size-1.5 rounded-full ${dotColor}`} />
            {isError ? "Error" : running ? "Listening" : "Idle"}
          </span>
        </header>

        {!vbInstalled && (
          <section className="flex flex-col gap-4 rounded-2xl border border-blue-200 bg-blue-50 p-6">
            <div>
              <h2 className="text-sm font-semibold text-slate-800">
                Set up your virtual microphone
              </h2>
              <p className="mt-1 text-xs text-slate-500">
                microhone needs a free virtual audio device. Install it in one
                click — approve the prompt, click “Install Driver” in the
                installer, then reboot.
              </p>
            </div>
            <div className="flex gap-3">
              <button
                onClick={installVbCable}
                className="rounded-lg bg-blue-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-600"
              >
                Install virtual microphone
              </button>
              <button
                onClick={refreshDevices}
                className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-white"
              >
                Recheck
              </button>
            </div>
          </section>
        )}


        <section className="flex flex-col gap-5 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
          <label className="flex flex-col gap-1.5">
            <span className="text-sm font-medium text-slate-700">
              Output device
            </span>
            <select
              value={device}
              onChange={(e) => setDevice(e.target.value)}
              disabled={running}
              className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 disabled:opacity-50"
            >
              <option value="">Default output</option>
              {devices.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
            <span className="text-xs text-slate-400">
              Send into “CABLE Input”, then choose “CABLE Output” as your
              microphone in Discord, OBS or Zoom.
            </span>
          </label>

          {/* Level meter */}
          <div className="h-2.5 w-full overflow-hidden rounded-full bg-slate-100">
            <div
              className="h-full rounded-full bg-linear-to-r from-blue-400 to-blue-600 transition-[width] duration-75"
              style={{ width: `${Math.min(100, Math.round(level * 100))}%` }}
            />
          </div>

          <button
            onClick={running ? stop : start}
            className={`rounded-xl py-3 font-medium text-white shadow-lg transition-colors ${
              running
                ? "bg-red-500 shadow-red-500/20 hover:bg-red-600"
                : "bg-blue-500 shadow-blue-500/25 hover:bg-blue-600"
            }`}
          >
            {running ? "Stop" : "Start listening"}
          </button>

          <p className="text-center font-mono text-xs text-slate-400">
            {status}
          </p>
        </section>

        {pairing && (
          <section className="flex flex-col items-center gap-3 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <span className="text-sm font-medium text-slate-700">
              Scan to pair your phone
            </span>
            <div
              className="overflow-hidden rounded-xl border border-slate-100"
              dangerouslySetInnerHTML={{ __html: pairing.svg }}
            />
            <span className="break-all text-center font-mono text-[10px] text-slate-400">
              {pairing.link}
            </span>
          </section>
        )}

        {/* Options */}
        <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-400">
            Options
          </div>
          <div className="flex gap-3 py-2">
            <label className="flex min-w-0 flex-1 flex-col gap-1 text-sm">
              <span className="text-slate-500">Port</span>
              <input
                value={port}
                onChange={(e) => setPort(e.target.value)}
                disabled={running}
                className="w-full rounded-lg border border-slate-200 px-3 py-2 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 disabled:opacity-50"
              />
            </label>
            <label className="flex min-w-0 flex-1 flex-col gap-1 text-sm">
              <span className="text-slate-500">Latency (ms)</span>
              <input
                value={latency}
                onChange={(e) => setLatency(e.target.value)}
                disabled={running}
                className="w-full rounded-lg border border-slate-200 px-3 py-2 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100 disabled:opacity-50"
              />
            </label>
          </div>
          <div className="divide-y divide-slate-100">
            <SettingRow
              label="Require pairing"
              hint="Encrypt the audio; only your phone can connect"
              checked={secure}
              onChange={setSecure}
              disabled={running}
            />
            <SettingRow
              label="USB cable"
              hint="Lowest latency over adb"
              checked={usb}
              onChange={setUsb}
              disabled={running}
            />
            <SettingRow
              label="Raw PCM"
              hint="Skip the Opus codec"
              checked={pcm}
              onChange={setPcm}
              disabled={running}
            />
          </div>
        </section>
      </div>
    </main>
  );
}

export default App;
