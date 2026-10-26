"use client";

import { useState } from "react";
import Link from "next/link";
import { gateway, newIdempotencyKey, type ApiResult } from "@/lib/api";
import { notifyDataChanged, useSession } from "@/lib/session";
import { BACKOFFICE_URL, formatVnd, shortId } from "@/lib/format";
import type { Order, Stock, Wallet } from "@/lib/types";

// skuId cố định trong seed (Product Service V2/V5, Inventory Service V100). Mua theo SKU = biến thể cụ thể.
const KEYBOARD = "11111111-1111-1111-1111-111111111111"; // Bàn phím — switch Red
const MOUSE = "22222222-2222-2222-2222-222222222222"; // Chuột — màu Đen
const MONITOR = "33333333-3333-3333-3333-333333333333"; // chỉ còn 2
const LAPTOP = "44444444-4444-4444-4444-444444444444"; // 25 triệu > credit

type LogLine = { kind: "req" | "info" | "ok" | "bad"; text: string; meta?: string; link?: string };
type Ctx = {
  token: string;
  log: (line: LogLine) => void;
  req: <T>(label: string, path: string, opts?: Parameters<typeof gateway>[1]) => Promise<ApiResult<T>>;
};

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function prepareCart(ctx: Ctx, items: [string, number][]) {
  await ctx.req("Xóa giỏ", "/api/cart", { method: "DELETE", token: ctx.token });
  for (const [skuId, quantity] of items) {
    await ctx.req(`Thêm ${quantity} × SKU ${shortId(skuId)}`, "/api/cart/items", {
      method: "POST",
      token: ctx.token,
      body: { skuId, quantity },
    });
  }
}

async function waitOrder(ctx: Ctx, orderId: string): Promise<Order | null> {
  let last = "";
  for (let i = 0; i < 120; i++) {
    const r = await gateway<Order>(`/api/orders/${orderId}`, { token: ctx.token });
    if (r.ok && r.data) {
      const s = `${r.data.status}/${r.data.sagaState}`;
      if (s !== last) {
        ctx.log({ kind: "info", text: `poll → status=${r.data.status} sagaState=${r.data.sagaState}` });
        last = s;
      }
      if (r.data.status !== "PENDING") return r.data;
    }
    await sleep(500);
  }
  ctx.log({ kind: "bad", text: "Hết 60 giây mà đơn vẫn PENDING" });
  return null;
}

async function checkout(ctx: Ctx, key = newIdempotencyKey()) {
  return ctx.req<Order>(`Checkout (Idempotency-Key ${key.slice(0, 18)}…)`, "/api/orders/checkout", {
    method: "POST",
    token: ctx.token,
    headers: { "Idempotency-Key": key },
  });
}

async function wallet(ctx: Ctx) {
  return (await gateway<Wallet>("/api/wallet/me", { token: ctx.token })).data;
}

type Scenario = {
  id: string;
  title: string;
  pattern: string;
  description: string;
  run: (ctx: Ctx) => Promise<boolean>;
};

const SCENARIOS: Scenario[] = [
  {
    id: "happy",
    title: "1. Mua hàng thành công",
    pattern: "Saga orchestration · Outbox · Inbox",
    description: "Keyboard + Mouse → giữ hàng → giữ credit → commit + capture → CONFIRMED → giỏ được dọn.",
    async run(ctx) {
      const before = await wallet(ctx);
      await prepareCart(ctx, [[KEYBOARD, 1], [MOUSE, 1]]);
      const r = await checkout(ctx);
      if (!r.ok || !r.data) return false;
      const order = await waitOrder(ctx, r.data.orderId);
      const after = await wallet(ctx);
      ctx.log({
        kind: order?.status === "CONFIRMED" ? "ok" : "bad",
        text: `Đơn ${order?.status}. Credit khả dụng: ${formatVnd(before?.availableVnd)} → ${formatVnd(after?.availableVnd)}`,
        link: `/orders/${r.data.orderId}`,
      });
      return order?.status === "CONFIRMED";
    },
  },
  {
    id: "credit",
    title: "2. Thiếu credit → bồi hoàn",
    pattern: "Compensating transaction",
    description: "Laptop 25.000.000 ₫. Inventory GIỮ hàng, Payment từ chối, Saga RELEASE hàng rồi mới FAILED.",
    async run(ctx) {
      const stockBefore = (await gateway<Stock>(`/api/inventory/${LAPTOP}`)).data;
      await prepareCart(ctx, [[LAPTOP, 1]]);
      const r = await checkout(ctx);
      if (!r.ok || !r.data) return false;
      const order = await waitOrder(ctx, r.data.orderId);
      const stockAfter = (await gateway<Stock>(`/api/inventory/${LAPTOP}`)).data;
      ctx.log({
        kind: "info",
        text: `Laptop reserved: ${stockBefore?.reservedQuantity} → ${stockAfter?.reservedQuantity} (đã trả lại hàng đã giữ)`,
      });
      const ok = order?.status === "FAILED" && !!order.failureReason?.startsWith("CREDIT_REJECTED");
      ctx.log({ kind: ok ? "ok" : "bad", text: `failureReason = ${order?.failureReason}`, link: `/orders/${r.data.orderId}` });
      await ctx.req("Dọn giỏ", "/api/cart", { method: "DELETE", token: ctx.token });
      return ok;
    },
  },
  {
    id: "stock",
    title: "3. Hết hàng",
    pattern: "Atomic reservation · all-or-nothing",
    description: "3 × Monitor (chỉ còn 2). Inventory từ chối ngay → FAILED, không đụng tới credit.",
    async run(ctx) {
      await prepareCart(ctx, [[MONITOR, 3]]);
      const r = await checkout(ctx);
      if (!r.ok || !r.data) return false;
      const order = await waitOrder(ctx, r.data.orderId);
      const ok = !!order?.failureReason?.startsWith("INVENTORY_REJECTED");
      ctx.log({ kind: ok ? "ok" : "bad", text: `failureReason = ${order?.failureReason}`, link: `/orders/${r.data.orderId}` });
      await ctx.req("Dọn giỏ", "/api/cart", { method: "DELETE", token: ctx.token });
      return ok;
    },
  },
  {
    id: "idempotency",
    title: "4. Bấm đặt hàng 3 lần (cùng key)",
    pattern: "Idempotency-Key",
    description: "Gửi đồng thời 3 request cùng Idempotency-Key — mô phỏng client retry sau timeout. Chỉ có MỘT đơn.",
    async run(ctx) {
      await prepareCart(ctx, [[MOUSE, 1]]);
      const key = newIdempotencyKey("retry");
      const results = await Promise.all([checkout(ctx, key), checkout(ctx, key), checkout(ctx, key)]);
      const ids = new Set(results.map((r) => r.data?.orderId));
      const statuses = results.map((r) => r.status).join(", ");
      const ok = ids.size === 1 && !ids.has(undefined);
      ctx.log({ kind: ok ? "ok" : "bad", text: `HTTP status: ${statuses} — số orderId khác nhau: ${ids.size}` });
      const id = results[0].data?.orderId;
      if (id) await waitOrder(ctx, id);
      return ok;
    },
  },
  {
    id: "double",
    title: "5. Hai tab checkout cùng giỏ (khác key)",
    pattern: "Unique index trên (user, cart_revision)",
    description: "Hai request khác key cho CÙNG phiên bản giỏ → một 202, một 409 CHECKOUT_ALREADY_IN_PROGRESS.",
    async run(ctx) {
      await prepareCart(ctx, [[MOUSE, 1]]);
      const [a, b] = await Promise.all([checkout(ctx), checkout(ctx)]);
      const statuses = [a.status, b.status].sort();
      const ok = statuses[0] === 202 && statuses[1] === 409;
      ctx.log({ kind: ok ? "ok" : "bad", text: `HTTP status: ${statuses.join(" & ")}` });
      const winner = a.ok ? a : b;
      if (winner.data) await waitOrder(ctx, winner.data.orderId);
      return ok;
    },
  },
  {
    id: "security",
    title: "6. Bảo mật & validation",
    pattern: "JWT ở gateway + từng service",
    description: "Checkout không token → 401; token giả → 401; số lượng 0 → 400; sản phẩm ngừng bán → 422.",
    async run(ctx) {
      const results = [
        (await ctx.req("Checkout không token", "/api/orders/checkout", { method: "POST", headers: { "Idempotency-Key": "anon-12345678" } })).status === 401,
        (await ctx.req("Token giả mạo", "/api/cart", { token: "eyJhbGciOiJIUzI1NiJ9.e30.forged" })).status === 401,
        (await ctx.req("Số lượng 0", "/api/cart/items", { method: "POST", token: ctx.token, body: { skuId: KEYBOARD, quantity: 0 } })).status === 400,
        (await ctx.req("Sản phẩm inactive", "/api/cart/items", { method: "POST", token: ctx.token, body: { skuId: "55555555-5555-5555-5555-555555555555", quantity: 1 } })).status === 422,
        (await ctx.req("Checkout thiếu Idempotency-Key", "/api/orders/checkout", { method: "POST", token: ctx.token })).status === 400,
      ];
      const ok = results.every(Boolean);
      ctx.log({ kind: ok ? "ok" : "bad", text: ok ? "Tất cả request xấu đều bị chặn đúng mã lỗi" : "Có kiểm tra không như kỳ vọng" });
      return ok;
    },
  },
];

export default function ScenariosPage() {
  const session = useSession();
  const [logs, setLogs] = useState<Record<string, LogLine[]>>({});
  const [running, setRunning] = useState<string | null>(null);
  const [result, setResult] = useState<Record<string, boolean>>({});

  async function run(s: Scenario) {
    if (!session) return;
    setRunning(s.id);
    setLogs((l) => ({ ...l, [s.id]: [] }));
    const log = (line: LogLine) => setLogs((l) => ({ ...l, [s.id]: [...(l[s.id] ?? []), line] }));
    const ctx: Ctx = {
      token: session.token,
      log,
      async req(label, path, opts) {
        const r = await gateway<never>(path, opts);
        log({
          kind: "req",
          text: `${opts?.method ?? "GET"} ${path.split("?")[0]} → ${r.status}${r.error ? ` ${r.error.code}` : ""}`,
          meta: `${label} · ${Math.round(r.durationMs)}ms${r.headers.get("x-correlation-id") ? ` · corr ${shortId(r.headers.get("x-correlation-id"))}` : ""}`,
        });
        return r as never;
      },
    };
    let ok = false;
    try {
      ok = await s.run(ctx);
    } catch (e) {
      log({ kind: "bad", text: String(e) });
    }
    setResult((r) => ({ ...r, [s.id]: ok }));
    setRunning(null);
    notifyDataChanged();
  }

  if (!session) {
    return (
      <div className="card text-center">
        <p className="muted mb-3">Các kịch bản chạy bằng tài khoản của bạn (mỗi tài khoản có 10.000.000 ₫).</p>
        <Link className="btn btn-primary" href="/login">Đăng ký / đăng nhập</Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="h1">Kịch bản demo</h1>
        <p className="muted">
          Thay cho <code className="mono">scripts/demo.sh</code>. Mỗi kịch bản gọi API thật qua gateway và ghi lại từng request
          (status, thời gian, correlationId). Kịch bản sẽ <b>xóa giỏ hàng hiện tại</b>.
        </p>
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        {SCENARIOS.map((s) => (
          <div key={s.id} className="card flex flex-col gap-3">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="font-semibold">{s.title}</h2>
                <span className="badge mt-1 bg-accent-soft text-accent">{s.pattern}</span>
              </div>
              {s.id in result && (
                <span className={`badge ${result[s.id] ? "bg-ok-soft text-ok" : "bg-bad-soft text-bad"}`}>
                  {result[s.id] ? "ĐẠT" : "KHÔNG ĐẠT"}
                </span>
              )}
            </div>
            <p className="muted">{s.description}</p>
            <button className="btn btn-primary self-start" disabled={running !== null} onClick={() => run(s)}>
              {running === s.id ? "Đang chạy…" : "Chạy"}
            </button>
            {logs[s.id] && logs[s.id].length > 0 && (
              <ol className="max-h-72 space-y-1 overflow-y-auto rounded-lg bg-surface-2 p-3 text-xs">
                {logs[s.id].map((l, i) => (
                  <li key={i} className={l.kind === "ok" ? "text-ok" : l.kind === "bad" ? "text-bad" : l.kind === "info" ? "text-accent" : ""}>
                    <span className="mono">{l.text}</span>
                    {l.meta && <span className="block text-muted">{l.meta}</span>}
                    {l.link && (
                      <Link className="ml-1 underline" href={l.link}>xem Saga →</Link>
                    )}
                  </li>
                ))}
              </ol>
            )}
          </div>
        ))}
      </div>

      <div className="card space-y-3">
        <h2 className="h2">7. Sự cố hạ tầng (làm tay với Docker)</h2>
        <p className="muted">Những kịch bản này cần dừng container nên không chạy được từ trình duyệt:</p>
        <ul className="list-disc space-y-2 pl-5 text-sm">
          <li>
            <b>Payment down giữa Saga:</b> <code className="mono">docker compose stop payment-service</code> → chạy kịch bản 1 →
            đơn đứng ở <code className="mono">INVENTORY_RESERVED</code> (command nằm chờ trong queue durable) →{" "}
            <code className="mono">docker compose start payment-service</code> → đơn CONFIRMED, credit chỉ trừ một lần.
          </li>
          <li>
            <b>Circuit breaker:</b> thêm hàng vào giỏ → <code className="mono">docker compose stop product-service</code> → mở Giỏ hàng
            vài lần: vẫn hiển thị nhưng không có giá; trang Vận hành của backoffice cho thấy breaker <code className="mono">product-service</code> OPEN.
          </li>
          <li>
            <b>Poison message → DLQ:</b> xem nút “Gửi message hỏng” trong trang{" "}
            <a className="text-accent underline" href={`${BACKOFFICE_URL}/operations`}>Vận hành của backoffice ↗</a>.
          </li>
        </ul>
      </div>
    </div>
  );
}
