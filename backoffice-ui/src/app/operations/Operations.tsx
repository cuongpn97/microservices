"use client";

import { useSearchParams } from "next/navigation";
import { useSession } from "@/lib/session";
import { DlqPanel, SagaInspector } from "./OperationsView";

export function Operations() {
  const admin = useSession("admin");
  const sagaId = useSearchParams().get("saga");
  if (!admin) return null;
  return (
    <div className="space-y-6">
      <div>
        <h1 className="h1">Vận hành</h1>
        <p className="muted">Endpoint /admin/** gọi thẳng từng service qua BFF (gateway không public chúng).</p>
      </div>
      <SagaInspector token={admin.token} initialSagaId={sagaId} />
      <DlqPanel token={admin.token} />
    </div>
  );
}
