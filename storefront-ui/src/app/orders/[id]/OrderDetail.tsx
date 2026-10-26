"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import { gateway } from "@/lib/api";
import { notifyDataChanged, useSession } from "@/lib/session";
import { BACKOFFICE_URL, formatVnd } from "@/lib/format";
import type { ApiError, Order } from "@/lib/types";
import { SagaTracker, StatusBadge } from "@/components/SagaTracker";
import { ErrorBox } from "@/components/ErrorBox";

export function OrderDetail({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const session = useSession();
  const [order, setOrder] = useState<Order | null>(null);
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
    };
    void load();
    if (done) return;
    const t = setInterval(load, 700);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [id, session, done]);

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
        <SagaTracker order={order} />
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
        <p className="text-xs text-muted">
          Nhật ký chuyển trạng thái chi tiết (bảng <code className="mono">saga_transition</code>) là dữ liệu vận hành —
          xem trong{" "}
          <a className="text-accent underline" href={`${BACKOFFICE_URL}/operations?saga=${order.orderId}`}>
            backoffice ↗
          </a>{" "}
          (cần tài khoản ADMIN).
        </p>
      </section>

      <section className="card h-fit">
        <h2 className="h2 mb-3">Snapshot giá lúc checkout</h2>
        <table className="table">
          <tbody>
            {order.items.map((i) => (
              <tr key={i.skuId}>
                <td>
                  <div>
                    {i.productName} <span className="text-muted">× {i.quantity}</span>
                  </div>
                  {i.variantName && <div className="text-xs text-muted">Phân loại: {i.variantName}</div>}
                  {order.status === "CONFIRMED" && i.productId && (
                    <Link className="text-xs text-accent underline" href={`/products/${i.productId}#reviews`}>
                      Viết đánh giá
                    </Link>
                  )}
                </td>
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
        <p className="mt-2 text-xs text-muted">
          Bất biến: trigger DB cấm UPDATE/DELETE trên order_item — đổi giá/tên sản phẩm sau này không ảnh hưởng đơn này.
        </p>
      </section>
    </div>
  );
}
