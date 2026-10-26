"use client";

import { useEffect, useState } from "react";

type Toast = { id: number; kind: "ok" | "bad" | "info"; text: string };
let nextId = 1;
const listeners = new Set<(t: Toast) => void>();

export function toast(text: string, kind: Toast["kind"] = "info") {
  const t = { id: nextId++, kind, text };
  listeners.forEach((l) => l(t));
}

export function ToastHost() {
  const [items, setItems] = useState<Toast[]>([]);
  useEffect(() => {
    const onToast = (t: Toast) => {
      setItems((prev) => [...prev, t]);
      setTimeout(() => setItems((prev) => prev.filter((x) => x.id !== t.id)), 4500);
    };
    listeners.add(onToast);
    return () => {
      listeners.delete(onToast);
    };
  }, []);
  return (
    <div className="pointer-events-none fixed right-4 bottom-4 z-50 flex w-80 flex-col gap-2">
      {items.map((t) => (
        <div
          key={t.id}
          role="status"
          className={`pointer-events-auto rounded-lg border px-4 py-3 text-sm shadow-lg ${
            t.kind === "ok"
              ? "border-ok/30 bg-ok-soft text-ok"
              : t.kind === "bad"
                ? "border-bad/30 bg-bad-soft text-bad"
                : "border-border bg-surface text-text"
          }`}
        >
          {t.text}
        </div>
      ))}
    </div>
  );
}
