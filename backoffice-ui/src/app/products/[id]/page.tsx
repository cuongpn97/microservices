import { Suspense } from "react";
import { ProductEditor } from "./ProductEditor";

export default function EditProductPage({ params }: PageProps<"/products/[id]">) {
  return (
    <Suspense fallback={<p className="muted">Đang tải…</p>}>
      <ProductEditor params={params} />
    </Suspense>
  );
}
