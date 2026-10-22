import { Suspense } from "react";
import { OrderDetail } from "./OrderDetail";

export default function OrderPage({ params }: PageProps<"/orders/[id]">) {
  return (
    <Suspense fallback={<p className="muted">Đang tải…</p>}>
      <OrderDetail params={params} />
    </Suspense>
  );
}
