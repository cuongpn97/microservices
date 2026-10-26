"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { gateway, newIdempotencyKey } from "@/lib/api";
import { notifyDataChanged, useSession } from "@/lib/session";
import { useCart, useWallet } from "@/lib/hooks";
import { formatVnd, shortId } from "@/lib/format";
import type { ApiError, Order } from "@/lib/types";
import { toast } from "@/components/Toast";
import { ErrorBox } from "@/components/ErrorBox";
import { ProductImage } from "@/components/ProductImage";

export default function CartPage() {
  const session = useSession();
  const router = useRouter();
  const { cart } = useCart(true);
  const { wallet } = useWallet();
  // Key sinh trong event handler (không sinh lúc prerender): giữ nguyên giữa các lần retry cho tới khi thành công.
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  if (!session) {
    return (
      <div className="card text-center">
        <p className="muted mb-3">Giỏ hàng gắn với user trong JWT — hãy đăng nhập.</p>
        <Link className="btn btn-primary" href="/login">Đăng nhập</Link>
      </div>
    );
  }
  const token = session.token;

  async function setQuantity(skuId: string, quantity: number) {
    const r =
      quantity <= 0
        ? await gateway(`/api/cart/items/${skuId}`, { method: "DELETE", token })
        : await gateway(`/api/cart/items/${skuId}`, { method: "PUT", token, body: { quantity } });
    if (!r.ok) toast(`${r.error?.code}: ${r.error?.message}`, "bad");
    notifyDataChanged();
  }

  async function checkout() {
    setBusy(true);
    setError(null);
    const idempotencyKey = key || newIdempotencyKey();
    setKey(idempotencyKey);
    const r = await gateway<Order>("/api/orders/checkout", {
      method: "POST",
      token,
      headers: { "Idempotency-Key": idempotencyKey },
    });
    setBusy(false);
    if (!r.ok || !r.data) {
      setError(r.error);
      return;
    }
    const replayed = r.headers.get("Idempotent-Replayed") === "true";
    toast(
      replayed
        ? `HTTP 200 — key đã dùng, trả lại đúng đơn ${shortId(r.data.orderId)} (không tạo đơn mới)`
        : `HTTP 202 — đơn ${shortId(r.data.orderId)} đã tạo, Saga bắt đầu`,
      "ok",
    );
    setKey("");
    router.push(`/orders/${r.data.orderId}`);
  }

  const items = cart?.items ?? [];
  const total = cart?.estimatedTotalVnd ?? null;
  const insufficient = wallet && total !== null && total > wallet.availableVnd;

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
      <section className="card">
        <div className="mb-4 flex items-center justify-between">
          <h1 className="h1">Giỏ hàng</h1>
          {items.length > 0 && (
            <button
              className="btn btn-sm"
              onClick={async () => {
                await gateway("/api/cart", { method: "DELETE", token });
                notifyDataChanged();
              }}
            >
              Xóa giỏ
            </button>
          )}
        </div>
        {cart && !cart.pricingAvailable && items.length > 0 && (
          <div className="mb-4 rounded-lg border border-warn/30 bg-warn-soft px-3 py-2 text-sm text-warn">
            Product Service không phản hồi (circuit breaker có thể đang OPEN) — Cart Service trả giỏ <b>không kèm giá</b> thay vì lỗi.
          </div>
        )}
        {items.length === 0 ? (
          <p className="muted">
            Giỏ trống. <Link className="text-accent underline" href="/">Chọn sản phẩm</Link>
          </p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Sản phẩm</th>
                <th className="text-right">Đơn giá</th>
                <th className="text-center">SL</th>
                <th className="text-right">Thành tiền</th>
              </tr>
            </thead>
            <tbody>
              {items.map((i) => (
                <tr key={i.skuId}>
                  <td>
                    <div className="flex items-center gap-3">
                      <ProductImage src={i.imageUrl} alt={i.productName ?? "?"} className="w-14 shrink-0" sizes="56px" />
                      <div className="min-w-0">
                        {i.productSlug ? (
                          <Link className="font-medium hover:text-accent" href={`/products/${i.productSlug}`}>{i.productName}</Link>
                        ) : (
                          <span className="mono">{shortId(i.skuId)}</span>
                        )}
                        <div className="flex flex-wrap gap-1 text-xs text-muted">
                          {i.variantName && <span>Phân loại: {i.variantName}</span>}
                          {i.skuCode && <span className="mono">· {i.skuCode}</span>}
                        </div>
                        {!i.active && <span className="badge bg-bad-soft text-bad">Ngừng bán</span>}
                      </div>
                    </div>
                  </td>
                  <td className="text-right">{formatVnd(i.unitPriceVnd)}</td>
                  <td>
                    <div className="flex items-center justify-center gap-1">
                      <button className="btn btn-sm" aria-label="Giảm" onClick={() => setQuantity(i.skuId, i.quantity - 1)}>−</button>
                      <span className="w-8 text-center">{i.quantity}</span>
                      <button className="btn btn-sm" aria-label="Tăng" onClick={() => setQuantity(i.skuId, i.quantity + 1)}>+</button>
                    </div>
                  </td>
                  <td className="text-right font-medium">{formatVnd(i.lineTotalVnd)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="muted mt-4">
          Giỏ chỉ lưu <code className="mono">skuId + quantity</code> (SKU = biến thể cụ thể, ví dụ áo Đen size M). Giá hiển thị là giá hiện tại; giá chốt được Order
          Service lấy lại từ Product Service tại thời điểm checkout. Revision giỏ: <span className="mono">{cart?.revision ?? "—"}</span>
        </p>
      </section>

      <aside className="card h-fit space-y-4">
        <h2 className="h2">Thanh toán</h2>
        <div className="space-y-1 text-sm">
          <div className="flex justify-between"><span className="text-muted">Tạm tính</span><b>{formatVnd(total)}</b></div>
          <div className="flex justify-between"><span className="text-muted">Credit khả dụng</span><span>{formatVnd(wallet?.availableVnd)}</span></div>
        </div>
        {insufficient && (
          <p className="rounded-lg bg-warn-soft px-3 py-2 text-xs text-warn">
            Tổng vượt credit: đơn vẫn được tạo, Inventory sẽ giữ hàng, Payment từ chối → Saga <b>bồi hoàn</b> (trả hàng) → FAILED.
          </p>
        )}
        <div>
          <label className="label" htmlFor="key">Idempotency-Key</label>
          <div className="flex gap-2">
            <input id="key" className="input mono" value={key} placeholder="tự sinh khi đặt hàng" onChange={(e) => setKey(e.target.value)} />
            <button className="btn btn-sm" title="Sinh key mới" onClick={() => setKey(newIdempotencyKey())}>↻</button>
          </div>
          <p className="mt-1 text-xs text-muted">
            Client retry với cùng key (ví dụ sau timeout mạng) → nhận lại đúng đơn cũ, không mua trùng. Thử trong{" "}
            <Link className="text-accent underline" href="/demo">Kịch bản demo</Link>.
          </p>
        </div>
        <ErrorBox error={error} />
        <button className="btn btn-primary w-full" disabled={busy || items.length === 0} onClick={checkout}>
          {busy ? "Đang gửi…" : "Đặt hàng"}
        </button>
      </aside>
    </div>
  );
}
