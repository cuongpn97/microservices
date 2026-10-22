"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { call, gateway } from "@/lib/api";
import { saveSession, useSession } from "@/lib/session";
import { formatTime, formatVnd, shortId } from "@/lib/format";
import type { ApiError, Product, SagaView, Stock, TokenResponse } from "@/lib/types";
import { ErrorBox } from "@/components/ErrorBox";
import { StatusBadge } from "@/components/SagaTracker";
import { toast } from "@/components/Toast";

const SERVICES = ["auth", "product", "cart", "inventory", "payment", "order"] as const;
const DLQ_OWNERS = ["cart", "inventory", "payment", "order"] as const;

type Health = { status: string };
type Breaker = { state: string; failureRate: string; bufferedCalls: number; failedCalls: number; notPermittedCalls: number };
type DlqMessage = {
  messageId: string;
  eventType: string | null;
  originalRoutingKey: string | null;
  attempt: number | null;
  failureReason: string | null;
  lastError: string | null;
  failedAt: string | null;
  body: string;
};

function parseMetric(text: string, name: string): number | null {
  const line = text.split("\n").find((l) => l.startsWith(name + "{") || l.startsWith(name + " "));
  return line ? Number(line.trim().split(" ").pop()) : null;
}

export default function AdminPage() {
  const admin = useSession("admin");
  if (!admin) return <AdminLogin />;
  return <AdminDashboard token={admin.token} />;
}

function AdminLogin() {
  const [email, setEmail] = useState("admin@webshop.local");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<ApiError | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const r = await gateway<TokenResponse>("/api/auth/login", { method: "POST", body: { email, password } });
    if (!r.ok || !r.data) return setError(r.error);
    if (!r.data.roles.includes("ADMIN")) {
      return setError({ status: 403, code: "NOT_ADMIN", message: "Tài khoản này không có role ADMIN" });
    }
    saveSession("admin", {
      token: r.data.accessToken,
      userId: r.data.userId,
      email,
      roles: r.data.roles,
      expiresAt: r.data.expiresAt,
    });
  }

  return (
    <form onSubmit={submit} className="card mx-auto max-w-md space-y-4">
      <h1 className="h1">Quản trị</h1>
      <p className="muted">
        Đăng nhập bằng tài khoản ADMIN được tạo sẵn từ <code className="mono">ADMIN_EMAIL</code> /{" "}
        <code className="mono">ADMIN_PASSWORD</code> trong file <code className="mono">.env</code>. Phiên admin tách riêng phiên mua hàng.
      </p>
      <div>
        <label className="label" htmlFor="a-email">Email</label>
        <input id="a-email" className="input" value={email} onChange={(e) => setEmail(e.target.value)} />
      </div>
      <div>
        <label className="label" htmlFor="a-pass">Mật khẩu</label>
        <input id="a-pass" type="password" className="input" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="giá trị ADMIN_PASSWORD" />
      </div>
      <ErrorBox error={error} />
      <button className="btn btn-primary w-full">Đăng nhập quản trị</button>
    </form>
  );
}

function AdminDashboard({ token }: { token: string }) {
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="h1">Quản trị & vận hành</h1>
          <p className="muted">Các endpoint /admin/** và /actuator/** gọi thẳng từng service (gateway không public chúng).</p>
        </div>
        <button className="btn btn-sm" onClick={() => saveSession("admin", null)}>Đăng xuất admin</button>
      </div>
      <SystemOverview token={token} />
      <div className="grid gap-6 lg:grid-cols-2">
        <StockManager token={token} />
        <SagaInspector token={token} />
      </div>
      <DlqPanel token={token} />
    </div>
  );
}

function SystemOverview({ token }: { token: string }) {
  const [rows, setRows] = useState<Record<string, { health: string; backlog: number | null; dlq: number | null }>>({});
  const [breakers, setBreakers] = useState<Record<string, Breaker>>({});

  const load = useCallback(async () => {
    const entries = await Promise.all(
      SERVICES.map(async (s) => {
        const [health, prom, dlq] = await Promise.all([
          call<Health>(s, "/actuator/health"),
          call<string>(s, "/actuator/prometheus"),
          (DLQ_OWNERS as readonly string[]).includes(s) ? call<Record<string, number>>(s, "/admin/dlq", { token }) : null,
        ]);
        const text = typeof prom.data === "string" ? prom.data : "";
        return [
          s,
          {
            health: health.data?.status ?? (health.status === 0 ? "DOWN" : `HTTP ${health.status}`),
            backlog: parseMetric(text, "webshop_outbox_backlog"),
            dlq: dlq?.ok && dlq.data ? Object.values(dlq.data).reduce((a, b) => a + Math.max(b, 0), 0) : null,
          },
        ] as const;
      }),
    );
    setRows(Object.fromEntries(entries));
    const result: Record<string, Breaker> = {};
    for (const s of ["cart", "order"]) {
      const r = await call<{ circuitBreakers: Record<string, Breaker> }>(s, "/actuator/circuitbreakers", { token });
      for (const [name, b] of Object.entries(r.data?.circuitBreakers ?? {})) result[`${s} → ${name}`] = b;
    }
    setBreakers(result);
  }, [token]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải dữ liệu định kỳ từ API
    void load();
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, [load]);

  return (
    <div className="grid gap-6 lg:grid-cols-[1.3fr_1fr]">
      <div className="card overflow-x-auto">
        <h2 className="h2 mb-3">Sức khỏe hệ thống</h2>
        <table className="table">
          <thead>
            <tr><th>Service</th><th>Health</th><th className="text-right">Outbox chưa gửi</th><th className="text-right">Message trong DLQ</th></tr>
          </thead>
          <tbody>
            {SERVICES.map((s) => (
              <tr key={s}>
                <td className="font-medium">{s}-service</td>
                <td><span className={`badge ${rows[s]?.health === "UP" ? "bg-ok-soft text-ok" : "bg-bad-soft text-bad"}`}>{rows[s]?.health ?? "…"}</span></td>
                <td className="text-right tabular-nums">{rows[s]?.backlog ?? "—"}</td>
                <td className={`text-right tabular-nums ${rows[s]?.dlq ? "font-semibold text-bad" : ""}`}>{rows[s]?.dlq ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="mt-2 text-xs text-muted">Outbox backlog: metric <code className="mono">webshop_outbox_backlog</code> (cập nhật mỗi 10s).</p>
      </div>
      <div className="card">
        <h2 className="h2 mb-3">Circuit breakers</h2>
        <div className="space-y-2">
          {Object.entries(breakers).map(([name, b]) => (
            <div key={name} className="flex items-center justify-between gap-3 rounded-lg bg-surface-2 px-3 py-2 text-sm">
              <div>
                <div className="font-medium">{name}</div>
                <div className="text-xs text-muted">
                  {b.failureRate.startsWith("-") ? "chưa đủ lời gọi để tính tỉ lệ lỗi" : `lỗi ${b.failureRate}`} · {b.failedCalls}/{b.bufferedCalls} lời gọi · bị chặn {b.notPermittedCalls}
                </div>
              </div>
              <span className={`badge ${b.state === "CLOSED" ? "bg-ok-soft text-ok" : b.state === "OPEN" ? "bg-bad-soft text-bad" : "bg-warn-soft text-warn"}`}>{b.state}</span>
            </div>
          ))}
          {Object.keys(breakers).length === 0 && <p className="muted">Đang tải…</p>}
        </div>
        <p className="mt-3 text-xs text-muted">Mỗi dependency một breaker. Thử: <code className="mono">docker compose stop product-service</code> rồi mở Giỏ hàng vài lần.</p>
      </div>
    </div>
  );
}

function StockManager({ token }: { token: string }) {
  const [rows, setRows] = useState<(Product & { stock: Stock | null })[]>([]);
  const [edits, setEdits] = useState<Record<string, number>>({});

  const load = useCallback(async () => {
    const r = await gateway<{ items: Product[] }>("/api/products?size=50");
    const items = await Promise.all(
      (r.data?.items ?? []).map(async (p) => ({ ...p, stock: (await gateway<Stock>(`/api/inventory/${p.id}`)).data })),
    );
    setRows(items);
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải dữ liệu lần đầu
    void load();
  }, [load]);

  async function save(productId: string) {
    const r = await gateway<Stock>(`/api/inventory/${productId}`, {
      method: "PUT",
      token,
      body: { stockOnHand: edits[productId] },
    });
    toast(r.ok ? "Đã cập nhật tồn kho" : `${r.error?.code}: ${r.error?.message}`, r.ok ? "ok" : "bad");
    void load();
  }

  return (
    <div className="card overflow-x-auto">
      <h2 className="h2 mb-3">Tồn kho</h2>
      <table className="table">
        <thead>
          <tr><th>Sản phẩm</th><th className="text-right">Giá</th><th className="text-right">Giữ</th><th>Tồn kho</th></tr>
        </thead>
        <tbody>
          {rows.map((p) => (
            <tr key={p.id}>
              <td>{p.name}</td>
              <td className="text-right">{formatVnd(p.priceVnd)}</td>
              <td className="text-right">{p.stock?.reservedQuantity ?? "—"}</td>
              <td>
                <div className="flex gap-2">
                  <input
                    aria-label={`Tồn kho ${p.name}`}
                    type="number"
                    min={0}
                    className="input w-24"
                    value={edits[p.id] ?? p.stock?.stockOnHand ?? 0}
                    onChange={(e) => setEdits({ ...edits, [p.id]: Number(e.target.value) })}
                  />
                  <button className="btn btn-sm" onClick={() => save(p.id)} disabled={edits[p.id] === undefined}>Lưu</button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-xs text-muted">PUT /api/inventory/&#123;id&#125; (ADMIN). Không cho đặt thấp hơn lượng đang giữ → 409.</p>
    </div>
  );
}

function SagaInspector({ token }: { token: string }) {
  const [attention, setAttention] = useState<SagaView[]>([]);
  const [lookup, setLookup] = useState("");
  const [saga, setSaga] = useState<SagaView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    const load = async () => {
      const r = await call<SagaView[]>("order", "/admin/sagas/attention", { token });
      if (r.ok && r.data) setAttention(r.data);
    };
    void load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [token]);

  async function find(e: React.FormEvent) {
    e.preventDefault();
    const r = await call<SagaView>("order", `/admin/sagas/${lookup.trim()}`, { token });
    setSaga(r.ok ? r.data : null);
    setError(r.ok ? null : r.error);
  }

  return (
    <div className="card space-y-4">
      <h2 className="h2">Saga</h2>
      <div>
        <p className="label">Cần can thiệp (needs_attention)</p>
        {attention.length === 0 ? (
          <p className="muted">Không có — mọi Saga đang tự phục hồi được.</p>
        ) : (
          attention.map((s) => (
            <div key={s.sagaId} className="mono flex justify-between">
              <Link className="text-accent underline" href={`/orders/${s.sagaId}`}>{shortId(s.sagaId)}</Link>
              <span>{s.state} · {s.lastError}</span>
            </div>
          ))
        )}
      </div>
      <form onSubmit={find} className="flex gap-2">
        <input className="input mono" placeholder="orderId / sagaId" value={lookup} onChange={(e) => setLookup(e.target.value)} />
        <button className="btn whitespace-nowrap">Tra cứu</button>
      </form>
      <ErrorBox error={error} />
      {saga && (
        <div className="space-y-2 text-sm">
          <div className="flex flex-wrap gap-2">
            <StatusBadge status={saga.state} />
            <span className="badge bg-surface-2">inventory {saga.inventoryStep}</span>
            <span className="badge bg-surface-2">payment {saga.paymentStep}</span>
            <span className="badge bg-surface-2">attempts {saga.stepAttempts}</span>
          </div>
          <ol className="space-y-1">
            {saga.transitions.map((t, i) => (
              <li key={i} className="mono">
                {formatTime(t.created_at)} {t.from_state ?? "∅"} → {t.to_state} <span className="text-muted">({t.trigger})</span>
              </li>
            ))}
          </ol>
          {saga.state !== "COMPLETED" && saga.state !== "FAILED" && (
            <button
              className="btn btn-sm"
              onClick={async () => {
                const r = await call("order", `/admin/sagas/${saga.sagaId}/retry-now`, { method: "POST", token });
                toast(r.ok ? "Watcher sẽ xử lý Saga ở lần quét tới" : r.error?.message ?? "Lỗi", r.ok ? "ok" : "bad");
              }}
            >
              Retry now
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function DlqPanel({ token }: { token: string }) {
  const [depths, setDepths] = useState<Record<string, { owner: string; depth: number }>>({});
  const [peek, setPeek] = useState<{ dlq: string; messages: DlqMessage[] } | null>(null);

  const load = useCallback(async () => {
    const all: Record<string, { owner: string; depth: number }> = {};
    for (const owner of DLQ_OWNERS) {
      const r = await call<Record<string, number>>(owner, "/admin/dlq", { token });
      for (const [dlq, depth] of Object.entries(r.data ?? {})) all[dlq] = { owner, depth };
    }
    setDepths(all);
  }, [token]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải dữ liệu định kỳ từ API
    void load();
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, [load]);

  async function sendPoison() {
    const r = await fetch("/bff-tools/poison", { method: "POST", headers: { authorization: `Bearer ${token}` } });
    const body = await r.json();
    toast(r.ok ? `Đã gửi ${body.messageId} → payment.command.reserve` : `${body.code}: ${body.message}`, r.ok ? "ok" : "bad");
    setTimeout(load, 1500);
  }

  return (
    <div className="card space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="h2">Dead-letter queues</h2>
          <p className="muted">Lỗi tạm thời: retry 1s → 3s → 9s (± jitter) rồi vào DLQ. Lỗi vĩnh viễn (JSON hỏng) vào DLQ ngay.</p>
        </div>
        <button className="btn btn-danger" onClick={sendPoison}>Gửi message hỏng (poison)</button>
      </div>
      <table className="table">
        <thead>
          <tr><th>DLQ</th><th>Service</th><th className="text-right">Số message</th><th /></tr>
        </thead>
        <tbody>
          {Object.entries(depths).map(([dlq, { owner, depth }]) => (
            <tr key={dlq}>
              <td className="mono">{dlq}</td>
              <td>{owner}</td>
              <td className={`text-right tabular-nums ${depth > 0 ? "font-semibold text-bad" : ""}`}>{depth}</td>
              <td className="text-right whitespace-nowrap">
                <button
                  className="btn btn-sm"
                  disabled={depth <= 0}
                  onClick={async () => {
                    const r = await call<DlqMessage[]>(owner, `/admin/dlq/${dlq}/messages?max=5`, { token });
                    setPeek({ dlq, messages: r.data ?? [] });
                  }}
                >
                  Xem
                </button>{" "}
                <button
                  className="btn btn-sm"
                  disabled={depth <= 0}
                  onClick={async () => {
                    const r = await call<{ redriven: number }>(owner, `/admin/dlq/${dlq}/redrive?max=10`, { method: "POST", token });
                    toast(r.ok ? `Redrive ${r.data?.redriven} message về queue gốc (messageId giữ nguyên → Inbox vẫn chặn trùng)` : "Lỗi redrive", r.ok ? "ok" : "bad");
                    setTimeout(load, 1500);
                  }}
                >
                  Redrive
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {peek && (
        <div className="space-y-2">
          <p className="label">5 message đầu của {peek.dlq} (xem không lấy ra)</p>
          {peek.messages.map((m) => (
            <div key={m.messageId} className="rounded-lg bg-surface-2 p-3 text-xs">
              <div className="mono font-semibold">{m.messageId}</div>
              <div className="mt-1 grid gap-x-4 gap-y-0.5 sm:grid-cols-2">
                <span>failureReason: <b className="text-bad">{m.failureReason}</b></span>
                <span>attempt: {m.attempt}</span>
                <span>routingKey gốc: <span className="mono">{m.originalRoutingKey}</span></span>
                <span>failedAt: {formatTime(m.failedAt)}</span>
              </div>
              <div className="mt-1 break-all text-muted">lastError: {m.lastError}</div>
              <div className="mono mt-1 break-all">body: {m.body}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
