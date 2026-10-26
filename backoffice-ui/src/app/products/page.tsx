import { Suspense } from "react";
import { ProductList } from "./ProductList";

export default function ProductsPage() {
  return (
    <Suspense fallback={<p className="muted">Đang tải…</p>}>
      <ProductList />
    </Suspense>
  );
}
