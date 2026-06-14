"use client";

import { motion } from "motion/react";

// "Barely any delay" — a live equalizer.
export function LatencyViz() {
  const bars = [0.45, 0.7, 1, 0.6, 0.85, 0.5];
  return (
    <div className="flex h-12 items-end gap-2">
      {bars.map((h, i) => (
        <motion.span
          key={i}
          className="w-2 origin-bottom rounded-full bg-blue-500"
          style={{ height: 46 }}
          animate={{ scaleY: [0.22, h, 0.22] }}
          transition={{
            duration: 0.9,
            repeat: Infinity,
            delay: i * 0.1,
            ease: "easeInOut",
          }}
        />
      ))}
    </div>
  );
}

// "Works with every app" — tiles lighting up in turn.
export function AppsViz() {
  const order = [0, 1, 3, 2];
  return (
    <div className="grid grid-cols-2 gap-2.5">
      {order.map((d, i) => (
        <motion.span
          key={i}
          className="size-6 rounded-lg bg-blue-500"
          animate={{ opacity: [0.25, 1, 0.25], scale: [0.9, 1, 0.9] }}
          transition={{
            duration: 1.8,
            repeat: Infinity,
            delay: d * 0.25,
            ease: "easeInOut",
          }}
        />
      ))}
    </div>
  );
}

// "WiFi or USB" — broadcasting WiFi arcs.
export function LinkViz() {
  const arcs = [
    "M16 26 a 10 10 0 0 1 16 0",
    "M12 22 a 16 16 0 0 1 24 0",
    "M8 18 a 22 22 0 0 1 32 0",
  ];
  return (
    <svg viewBox="0 0 48 34" className="h-12 w-17">
      {arcs.map((d, i) => (
        <motion.path
          key={i}
          d={d}
          fill="none"
          stroke="#3b82f6"
          strokeWidth="3"
          strokeLinecap="round"
          animate={{ opacity: [0.2, 1, 0.2] }}
          transition={{
            duration: 1.8,
            repeat: Infinity,
            delay: i * 0.25,
            ease: "easeInOut",
          }}
        />
      ))}
      <circle cx="24" cy="30" r="2.6" fill="#3b82f6" />
    </svg>
  );
}
