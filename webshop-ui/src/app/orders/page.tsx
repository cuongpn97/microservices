"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { gateway } from "@/lib/api";
import { useDataVersion, useSession } from "@/lib/session";
import { formatDateTime, formatVnd, shortId } from "@/lib/format";
import type { Order } from "@/lib/types";
import { StatusBadge } from "@/components/SagaTracker";

export default function OrdersPage() {
  const session = useSession();
  const version = useDataVersion();
  const [orders, setOrders] = useState<Order[] | null>(null);

  useEffect(() => {
    if (!session) return;
    let alive = true;
    const load = async () => {
      const r = await gateway<Order[]>("/api/orders", { token: session.token });
      if (alive && r.ok) setOrders(r.data);
    };
    void load();
    const t = setInterval(load, 2000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [session, version]);

  if (!session) {
    return (
      <div className="card text-center">
        <Link className="btn btn-primary" href="/login">Đăng nhập để xem đơn hàng</Link>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <h1 className="h1">Đơn hàng của tôi</h1>
      {!orders && <p className="muted">Đang tải…</p>}
      {orders?.length === 0 && <p className="muted">Chưa có đơn nào.</p>}
      {orders && orders.length > 0 && (
        <div className="card overflow-x-auto">
          <table className="table">
            <thead>
              <tr>
                <th>Mã</th>
                <th>Thời gian</th>
                <th>Trạng thái</th>
                <th>Saga</th>
                <th className="text-right">Tổng</th>
                <th>Lý do thất bại</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.orderId}>
                  <td>
                    <Link className="mono text-accent underline" href={`/orders/${o.orderId}`}>{shortId(o.orderId)}</Link>
                  </td>
                  <td className="whitespace-nowrap">{formatDateTime(o.createdAt)}</td>
                  <td><StatusBadge status={o.status} /></td>
                  <td><code className="mono">{o.sagaState}</code></td>
                  <td className="text-right">{formatVnd(o.totalVnd)}</td>
                  <td className="mono text-bad">{o.failureReason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
