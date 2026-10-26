import type { Order, SagaState, SagaView } from "@/lib/types";
import { formatTime } from "@/lib/format";

const STEP_INFO: Record<SagaState, { title: string; detail: string }> = {
  PENDING: { title: "Ghi nhận đơn", detail: "Order + Saga + command ReserveInventory được ghi nguyên tử (outbox), chờ Inventory" },
  INVENTORY_RESERVED: { title: "Đã giữ hàng", detail: "Inventory khóa dòng tồn kho, reserved += qty; đã gửi ReserveCredit tới Payment" },
  CONFIRMING: { title: "Đã giữ credit → xác nhận", detail: "Điểm không quay đầu: gửi CommitInventory + CaptureCredit (chỉ đi tiến)" },
  COMPLETED: { title: "Hoàn tất", detail: "Kho đã commit, credit đã capture. Đơn CONFIRMED, OrderConfirmed → Cart dọn giỏ" },
  COMPENSATING: { title: "Bồi hoàn", detail: "Release các tài nguyên đã/có thể đã giữ (credit trước, hàng sau)" },
  FAILED: { title: "Thất bại", detail: "Mọi tài nguyên đã được trả lại; không bị trừ tiền" },
};

const HAPPY_PATH: SagaState[] = ["PENDING", "INVENTORY_RESERVED", "CONFIRMING", "COMPLETED"];

/** Đường đi của Saga suy ra từ state hiện tại + failureReason (hoặc lịch sử thật nếu có token admin). */
function pathOf(order: Order, saga?: SagaView | null): SagaState[] {
  if (saga && saga.transitions.length > 0) {
    const visited = saga.transitions.map((t) => t.to_state as SagaState).filter((s, i, a) => a.indexOf(s) === i);
    if (saga.state === "COMPLETED" || saga.state === "FAILED") return visited;
    const rest = saga.state === "COMPENSATING" ? ["FAILED"] : HAPPY_PATH.slice(HAPPY_PATH.indexOf(saga.state) + 1);
    return [...visited, ...(rest as SagaState[])];
  }
  const state = order.sagaState ?? "PENDING";
  if (state !== "COMPENSATING" && state !== "FAILED") return HAPPY_PATH;
  const reason = order.failureReason ?? "";
  const path: SagaState[] = ["PENDING"];
  if (reason.startsWith("CREDIT_REJECTED") || reason.startsWith("PAYMENT_TIMEOUT")) path.push("INVENTORY_RESERVED");
  if (!reason.startsWith("INVENTORY_REJECTED")) path.push("COMPENSATING");
  path.push("FAILED");
  return path;
}

export function SagaTracker({ order, saga }: { order: Order; saga?: SagaView | null }) {
  const state = (saga?.state ?? order.sagaState ?? "PENDING") as SagaState;
  const path = pathOf(order, saga);
  const current = path.indexOf(state);
  const terminal = state === "COMPLETED" || state === "FAILED";
  const when = (s: string) => saga?.transitions.find((t) => t.to_state === s)?.created_at;

  return (
    <ol>
      {path.map((s, idx) => {
        const status = idx < current || (idx === current && terminal) ? "done" : idx === current ? "current" : "todo";
        const bad = s === "FAILED" || s === "COMPENSATING";
        const color =
          status === "done"
            ? bad
              ? "bg-bad text-white"
              : "bg-ok text-white"
            : status === "current"
              ? `${bad ? "bg-warn" : "bg-accent"} animate-pulse text-white`
              : "bg-surface-2 text-muted";
        return (
          <li key={s} className="relative flex gap-3 pb-5 last:pb-0">
            {idx < path.length - 1 && <span className="absolute top-7 left-3.5 h-[calc(100%-1.75rem)] w-px bg-border" />}
            <span className={`z-10 grid h-7 w-7 shrink-0 place-items-center rounded-full text-xs font-bold ${color}`}>
              {status === "done" ? (s === "FAILED" ? "✕" : "✓") : idx + 1}
            </span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-baseline gap-2">
                <span className={`font-medium ${status === "todo" ? "text-muted" : ""}`}>{STEP_INFO[s].title}</span>
                <code className="mono text-muted">{s}</code>
                {when(s) && <span className="mono text-muted">{formatTime(when(s))}</span>}
              </div>
              <p className="text-sm text-muted">{STEP_INFO[s].detail}</p>
            </div>
          </li>
        );
      })}
    </ol>
  );
}

export function StatusBadge({ status }: { status: string | null }) {
  const s = status ?? "—";
  const cls =
    s === "CONFIRMED" || s === "COMPLETED"
      ? "bg-ok-soft text-ok"
      : s === "FAILED"
        ? "bg-bad-soft text-bad"
        : s === "COMPENSATING"
          ? "bg-warn-soft text-warn"
          : "bg-accent-soft text-accent";
  return <span className={`badge ${cls}`}>{s}</span>;
}
