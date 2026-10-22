"use client";

import { useCallback, useEffect, useState } from "react";
import { gateway } from "./api";
import { useDataVersion, useSession } from "./session";
import type { Cart, Wallet } from "./types";

/** Ví của user hiện tại; tự thử lại khi ví chưa được khởi tạo (eventual consistency sau đăng ký). */
export function useWallet() {
  const session = useSession();
  const version = useDataVersion();
  const [wallet, setWallet] = useState<Wallet | null>(null);
  const [pending, setPending] = useState(false);

  const load = useCallback(async () => {
    if (!session) {
      setWallet(null);
      return;
    }
    const r = await gateway<Wallet>("/api/wallet/me", { token: session.token });
    setWallet(r.ok ? r.data : null);
    setPending(r.status === 404);
  }, [session]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải dữ liệu từ API khi phiên/dữ liệu đổi
    void load();
  }, [load, version]);

  useEffect(() => {
    if (!pending) return;
    const t = setInterval(load, 1000);
    return () => clearInterval(t);
  }, [pending, load]);

  return { wallet, walletPending: pending, reload: load };
}

export function useCart(pricing = false) {
  const session = useSession();
  const version = useDataVersion();
  const [cart, setCart] = useState<Cart | null>(null);

  const load = useCallback(async () => {
    if (!session) {
      setCart(null);
      return;
    }
    const r = await gateway<Cart>(`/api/cart?pricing=${pricing}`, { token: session.token });
    setCart(r.ok ? r.data : null);
  }, [session, pricing]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải dữ liệu từ API khi phiên/dữ liệu đổi
    void load();
  }, [load, version]);

  return { cart, reload: load };
}
