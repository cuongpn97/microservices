"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import { call, gateway } from "@/lib/api";
import { notifyDataChanged, useSession } from "@/lib/session";
import { formatTime, formatVnd } from "@/lib/format";
import type { ApiError, Order, SagaView } from "@/lib/types";
import { SagaTracker, StatusBadge } from "@/components/SagaTracker";
import { ErrorBox } from "@/components/ErrorBox";

export function OrderDetail({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const session = useSession();
  const admin = useSession("admin");
  const [order, setOrder] = useState<Order | null>(null);
  const [saga, setSaga] = useState<SagaView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [polls, setPolls] = useState(0);

  const done = order?.status === "CONFIRMED" || order?.status === "FAILED";

  useEffect(() => {
    if (!session) return;
    let alive = true;
    const load = async () => {
      const r = await gateway<Order>(`/api/orders/${id}`, { token: session.token });
      if (!alive) return;
      if (r.ok && r.data) {
        setOrder(r.data);
        setError(null);
        if (r.data.status !== "PENDING") notifyDataChanged();
      } else setError(r.error);
      setPolls((n) => n + 1);
      if (admin) {
        const s = await call<SagaView>("order", `/admin/sagas/${id}`, { token: admin.token });
        if (alive && s.ok) setSaga(s.data);
      }
    };
    void load();
    if (done) return;
    const t = setInterval(load, 700);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [id, session, admin, done]);

  if (!session) return <Link className="btn btn-primary" href="/login">Đăng nhập</Link>;
  if (error && !order) return <ErrorBox error={error} />;
  if (!order) return <p className="muted">Đang tải…</p>;

  return (
    <div className="grid gap-6 lg:grid-cols-[1.2fr_1fr]">
      <section className="card space-y-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="label">Đơn hàng</p>
            <h1 className="mono text-base break-all">{order.orderId}</h1>
          </div>
          <StatusBadge status={order.status} />
        </div>
        <SagaTracker order={order} saga={saga} />
        <p className="text-xs text-muted">
          {done
            ? `Saga đã kết thúc. Đã poll ${polls} lần (GET /api/orders/{id}).`
            : `Đang poll mỗi 0,7 giây… (${polls} lần). Saga chạy bất đồng bộ qua RabbitMQ.`}
        </p>
        {order.failureReason && (
          <div className="rounded-lg border border-bad/30 bg-bad-soft px-3 py-2 text-sm text-bad">
            <b>failureReason:</b> <code className="mono">{order.failureReason}</code>
          </div>
        )}
      </section>

      <section className="space-y-6">
        <div className="card">
          <h2 className="h2 mb-3">Snapshot giá lúc checkout</h2>
          <table className="table">
            <tbody>
              {order.items.map((i) => (
                <tr key={i.productId}>
                  <td>{i.productName} <span className="text-muted">× {i.quantity}</span></td>
                  <td className="text-right">{formatVnd(i.unitPriceVnd)}</td>
                  <td className="text-right font-medium">{formatVnd(i.lineTotalVnd)}</td>
                </tr>
              ))}
              <tr>
                <td colSpan={2} className="font-semibold">Tổng</td>
                <td className="text-right font-semibold">{formatVnd(order.totalVnd)}</td>
              </tr>
            </tbody>
          </table>
          <p className="mt-2 text-xs text-muted">Bất biến: trigger DB cấm UPDATE/DELETE trên order_item.</p>
        </div>

        <div className="card">
          <h2 className="h2 mb-2">Nhật ký Saga</h2>
          {saga ? (
            <>
              <div className="mb-3 flex flex-wrap gap-2 text-xs">
                <span className="badge bg-surface-2">inventory: {saga.inventoryStep}</span>
                <span className="badge bg-surface-2">payment: {saga.paymentStep}</span>
                <span className="badge bg-surface-2">attempts: {saga.stepAttempts}</span>
                {saga.needsAttention && <span className="badge bg-bad-soft text-bad">needs attention</span>}
              </div>
              <ol className="space-y-2 text-sm">
                {saga.transitions.map((t, i) => (
                  <li key={i} className="flex gap-3">
                    <span className="mono w-16 shrink-0 text-muted">{formatTime(t.created_at)}</span>
                    <span>
                      <code className="mono">{t.from_state ?? "∅"} → {t.to_state}</code>{" "}
                      <span className="text-muted">on {t.trigger}</span>
                      {t.detail && <span className="block text-xs text-muted">{t.detail}</span>}
                    </span>
                  </li>
                ))}
              </ol>
            </>
          ) : (
            <p className="muted">
              Lịch sử chuyển trạng thái chi tiết (bảng <code className="mono">saga_transition</code>) cần token ADMIN —{" "}
              <Link className="text-accent underline" href="/admin">đăng nhập quản trị</Link> để xem.
            </p>
          )}
        </div>
      </section>
    </div>
  );
}
