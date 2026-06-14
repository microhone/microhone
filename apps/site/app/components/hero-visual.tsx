"use client";

import { motion } from "motion/react";

function Bars() {
  const heights = [10, 18, 24, 14, 20, 12];
  return (
    <div className="flex items-end gap-1">
      {heights.map((h, i) => (
        <motion.span
          key={i}
          className="w-1 rounded-full bg-blue-500"
          style={{ height: h }}
          animate={{ scaleY: [0.4, 1, 0.4] }}
          transition={{
            duration: 1,
            repeat: Infinity,
            delay: i * 0.12,
            ease: "easeInOut",
          }}
        />
      ))}
    </div>
  );
}

export function HeroVisual() {
  return (
    <div className="relative mx-auto mt-16 w-full max-w-lg">
      <div className="absolute inset-x-6 -bottom-4 top-6 -z-10 rounded-[2.5rem] bg-blue-400/25 blur-3xl" />
      <div className="flex items-center justify-between gap-5 rounded-[2rem] border border-slate-200/80 bg-white p-7 shadow-xl shadow-blue-500/10">
        {/* Phone */}
        <div className="animate-floaty">
          <div className="flex h-28 w-16 items-center justify-center rounded-2xl border border-slate-200 bg-slate-50">
            <Bars />
          </div>
          <p className="mt-2.5 text-center text-xs font-medium text-slate-400">
            Phone
          </p>
        </div>

        {/* Signal flow */}
        <div className="relative h-px flex-1 bg-linear-to-r from-blue-200 via-blue-400 to-blue-200">
          {[0, 1, 2].map((i) => (
            <motion.span
              key={i}
              className="absolute top-1/2 size-2 -translate-y-1/2 rounded-full bg-blue-500 shadow shadow-blue-500/40"
              animate={{ left: ["0%", "100%"], opacity: [0, 1, 0] }}
              transition={{
                duration: 1.8,
                repeat: Infinity,
                delay: i * 0.6,
                ease: "easeInOut",
              }}
            />
          ))}
        </div>

        {/* Computer */}
        <div className="animate-floaty [animation-delay:0.7s]">
          <div className="flex h-24 w-32 items-center justify-center rounded-xl border border-slate-200 bg-slate-50">
            <span className="flex items-center gap-1.5 rounded-full bg-blue-50 px-2.5 py-1 text-xs font-medium text-blue-600">
              <span className="size-1.5 rounded-full bg-blue-500" />
              microhone
            </span>
          </div>
          <div className="mx-auto h-2 w-10 rounded-b-md bg-slate-200" />
          <p className="mt-1.5 text-center text-xs font-medium text-slate-400">
            Your PC
          </p>
        </div>
      </div>
    </div>
  );
}
