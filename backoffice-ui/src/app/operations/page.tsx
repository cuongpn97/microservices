import { Suspense } from "react";
import { Operations } from "./Operations";

export default function OperationsPage() {
  return (
    // Operations đọc ?saga=... (useSearchParams — dữ liệu runtime) → phải nằm trong Suspense khi bật Cache Components.
    <Suspense fallback={<p className="muted">Đang tải…</p>}>
      <Operations />
    </Suspense>
  );
}
