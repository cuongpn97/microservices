"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { gateway } from "@/lib/api";
import { useDataVersion, useSession } from "@/lib/session";
import { useWallet } from "@/lib/hooks";
import { formatDateTime, formatVnd, shortId } from "@/lib/format";
import type { LedgerEntry } from "@/lib/types";

const TYPE_STYLE: Record<LedgerEntry["type"], { label: string; cls: string; sign: string }> = {
  INITIAL_CREDIT: { label: "Credit ban đầu", cls: "bg-ok-soft text-ok", sign: "+" },
  RESERVE: { label: "Giữ credit", cls: "bg-warn-soft text-warn", sign: "⏸" },
  CAPTURE: { label: "Trừ tiền", cls: "bg-bad-soft text-bad", sign: "−" },
  RELEASE: { label: "Trả lại phần giữ", cls: "bg-accent-soft text-accent", sign: "↺" },
};

export default function WalletPage() {
  const session = useSession();
  const version = useDataVersion();
  const { wallet, walletPending } = useWallet();
  const [ledger, setLedger] = useState<LedgerEntry[]>([]);

  useEffect(() => {
    if (!session) return;
    let alive = true;
    const load = async () => {
      const r = await gateway<LedgerEntry[]>("/api/wallet/me/ledger?limit=100", { token: session.token });
      if (alive && r.ok && r.data) setLedger(r.data);
    };
    void load();
    const t = setInterval(load, 3000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [session, version]);

  if (!session) return <Link className="btn btn-primary" href="/login">Đăng nhập</Link>;

  const stats = [
    { label: "Số dư (balance)", value: wallet?.balanceVnd, hint: "Credit đang sở hữu" },
    { label: "Đang giữ (reserved)", value: wallet?.reservedVnd, hint: "Giữ cho đơn chưa hoàn tất" },
    { label: "Khả dụng (available)", value: wallet?.availableVnd, hint: "= balance − reserved, không bao giờ âm" },
    { label: "Đã chi (captured)", value: wallet?.totalCapturedVnd, hint: "Tổng các dòng CAPTURE" },
  ];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="h1">Ví credit nội bộ</h1>
        <p className="muted">Payment Service — sổ cái append-only (trigger DB cấm sửa/xóa lịch sử).</p>
      </div>
      {walletPending && (
        <div className="card border-warn/40 bg-warn-soft text-warn">
          404 WALLET_NOT_INITIALIZED — UserRegistered chưa được Payment Service xử lý xong. Đang thử lại mỗi giây…
        </div>
      )}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((s) => (
          <div key={s.label} className="card">
            <p className="label">{s.label}</p>
            <p className="text-2xl font-semibold tabular-nums">{formatVnd(s.value)}</p>
            <p className="mt-1 text-xs text-muted">{s.hint}</p>
          </div>
        ))}
      </div>
      <div className="card overflow-x-auto">
        <h2 className="h2 mb-3">Sổ cái</h2>
        <table className="table">
          <thead>
            <tr>
              <th>Thời gian</th>
              <th>Loại</th>
              <th className="text-right">Số tiền</th>
              <th className="text-right">Balance sau</th>
              <th className="text-right">Reserved sau</th>
              <th>Tham chiếu</th>
            </tr>
          </thead>
          <tbody>
            {ledger.map((e) => (
              <tr key={e.id}>
                <td className="whitespace-nowrap">{formatDateTime(e.createdAt)}</td>
                <td><span className={`badge ${TYPE_STYLE[e.type].cls}`}>{TYPE_STYLE[e.type].label}</span></td>
                <td className="text-right tabular-nums">{TYPE_STYLE[e.type].sign} {formatVnd(e.amountVnd)}</td>
                <td className="text-right tabular-nums">{formatVnd(e.balanceAfterVnd)}</td>
                <td className="text-right tabular-nums">{formatVnd(e.reservedAfterVnd)}</td>
                <td>
                  {e.type === "INITIAL_CREDIT" ? (
                    <span className="mono text-muted">user {shortId(e.referenceId)}</span>
                  ) : (
                    <Link className="mono text-accent underline" href={`/orders/${e.referenceId}`}>
                      đơn {shortId(e.referenceId)}
                    </Link>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
