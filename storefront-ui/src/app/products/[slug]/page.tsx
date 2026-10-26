import { Suspense } from "react";
import { ProductView } from "./ProductView";

export default function ProductPage({ params }: PageProps<"/products/[slug]">) {
  return (
    <Suspense fallback={<p className="muted">Đang tải…</p>}>
      <ProductView params={params} />
    </Suspense>
  );
}
