"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import { gateway } from "@/lib/api";
import { useSession } from "@/lib/session";
import { formatDateTime, STOREFRONT_URL } from "@/lib/format";
import type { ApiError, ProductDetail } from "@/lib/types";
import { ErrorBox } from "@/components/ErrorBox";
import { DetailsForm } from "../DetailsForm";
import { ImageManager } from "./ImageManager";
import { SkuManager } from "./SkuManager";

/**
 * Trang sửa một SPU. Mọi API ghi trả về chi tiết SPU mới nhất → component con gọi onChange(product) để cả trang
 * cập nhật (SKU thấy ảnh mới upload, header thấy trạng thái mới...) mà không phải tải lại.
 */
export function ProductEditor({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const admin = useSession("admin");
  const [product, setProduct] = useState<ProductDetail | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  // Chỉ dựng lại form thông tin khi CHÍNH nó vừa lưu — upload ảnh/sửa SKU không được xóa nội dung đang gõ dở.
  const [detailsKey, setDetailsKey] = useState(0);

  useEffect(() => {
    if (!admin) return;
    let alive = true;
    (async () => {
      const r = await gateway<ProductDetail>(`/api/products/manage/${id}`, { token: admin.token });
      if (!alive) return;
      if (r.ok) setProduct(r.data);
      else setError(r.error);
    })();
    return () => {
      alive = false;
    };
  }, [id, admin]);

  if (!admin) return null;
  if (error) return <ErrorBox error={error} />;
  if (!product) return <p className="muted">Đang tải…</p>;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <nav className="text-sm text-muted">
            <Link href="/products" className="hover:text-text">Sản phẩm</Link> / <span className="text-text">{product.name}</span>
          </nav>
          <h1 className="h1 mt-1 flex flex-wrap items-center gap-2">
            {product.name}
            <span className={`badge ${product.active ? "bg-ok-soft text-ok" : "bg-surface-2 text-muted"}`}>
              {product.active ? "Đang bán" : "Nháp"}
            </span>
          </h1>
          <p className="text-xs text-muted">
            version {product.version} · cập nhật {formatDateTime(product.updatedAt)} ·{" "}
            {product.ratingCount > 0 ? `${product.ratingAvg?.toFixed(1)}★ (${product.ratingCount} đánh giá)` : "chưa có đánh giá"}
          </p>
        </div>
        {product.active && (
          <a className="btn" href={`${STOREFRONT_URL}/products/${product.slug}`} target="_blank" rel="noreferrer">
            Xem ở cửa hàng ↗
          </a>
        )}
      </div>

      <DetailsForm
        key={detailsKey}
        product={product}
        token={admin.token}
        onSaved={(p) => {
          setProduct(p);
          setDetailsKey((k) => k + 1);
        }}
      />
      <ImageManager product={product} token={admin.token} onChange={setProduct} />
      <SkuManager product={product} token={admin.token} onChange={setProduct} />
    </div>
  );
}
