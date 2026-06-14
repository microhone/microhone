import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";

function App() {
  const [version, setVersion] = useState<string>("…");

  useEffect(() => {
    invoke<string>("app_version")
      .then(setVersion)
      .catch(() => setVersion("unknown"));
  }, []);

  return (
    <main className="flex h-full flex-col items-center justify-center gap-4 bg-black text-white">
      <span className="rounded-full border border-white/15 px-3 py-1 font-mono text-xs uppercase tracking-widest text-white/60">
        microhone desktop
      </span>
      <h1 className="text-3xl font-semibold tracking-tight">
        🎙️ microhone
      </h1>
      <p className="text-sm text-white/50">
        Virtual microphone host — skeleton (Faz 0)
      </p>
      <p className="font-mono text-xs text-white/30">core v{version}</p>
    </main>
  );
}

export default App;
