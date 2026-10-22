"use client";

import { useSyncExternalStore } from "react";

/**
 * Lưu access token trong localStorage — CHỈ phù hợp cho demo học tập.
 * Production: dùng cookie HttpOnly + SameSite do BFF quản lý để JavaScript (và XSS) không đọc được token.
 */
export type Session = { token: string; userId: string; email: string; roles: string[]; expiresAt: string };

const KEYS = { user: "webshop.session", admin: "webshop.adminSession" } as const;
type Kind = keyof typeof KEYS;

const listeners = new Set<() => void>();
const cache: Record<Kind, { raw: string | null; value: Session | null }> = {
  user: { raw: null, value: null },
  admin: { raw: null, value: null },
};

function read(kind: Kind): Session | null {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(KEYS[kind]);
  } catch {
    raw = null;
  }
  if (raw === cache[kind].raw) return cache[kind].value;
  let value: Session | null = null;
  try {
    value = raw ? (JSON.parse(raw) as Session) : null;
    if (value && new Date(value.expiresAt).getTime() < Date.now()) value = null;
  } catch {
    value = null;
  }
  cache[kind] = { raw, value };
  return value;
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  const onStorage = () => listener();
  window.addEventListener("storage", onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", onStorage);
  };
}

export function saveSession(kind: Kind, session: Session | null) {
  try {
    if (session) window.localStorage.setItem(KEYS[kind], JSON.stringify(session));
    else window.localStorage.removeItem(KEYS[kind]);
  } catch {
    /* private mode: phiên chỉ sống trong bộ nhớ */
  }
  listeners.forEach((l) => l());
}

export function useSession(kind: Kind = "user"): Session | null {
  return useSyncExternalStore(subscribe, () => read(kind), () => null);
}

/** Thông báo cho các component đang hiển thị ví/giỏ hàng rằng dữ liệu đã đổi. */
const dataListeners = new Set<() => void>();
let dataVersion = 0;
export function notifyDataChanged() {
  dataVersion++;
  dataListeners.forEach((l) => l());
}
export function useDataVersion(): number {
  return useSyncExternalStore(
    (l) => {
      dataListeners.add(l);
      return () => dataListeners.delete(l);
    },
    () => dataVersion,
    () => 0,
  );
}
