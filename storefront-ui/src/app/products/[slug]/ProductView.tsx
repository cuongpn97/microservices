"use client";

import { use, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { gateway } from "@/lib/api";
import { notifyDataChanged, useSession } from "@/lib/session";
import { formatPriceRange, formatVnd, SKU_HINTS } from "@/lib/format";
import type { ApiError, ProductDetail, Stock } from "@/lib/types";
import { ErrorBox } from "@/components/ErrorBox";
import { Markdown } from "@/components/Markdown";
import { ProductImage } from "@/components/ProductImage";
import { RatingLine } from "@/components/Stars";
import { toast } from "@/components/Toast";
import { Reviews } from "./Reviews";
import { defaultSelection, findSku, valueState, type Selection } from "./variants";

export function ProductView({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = use(params);
  const session = useSession();
  const [product, setProduct] = useState<ProductDetail | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [stock, setStock] = useState<Map<string, Stock>>(new Map());
  const [selection, setSelection] = useState<Selection | null>(null);
  const [activeImage, setActiveImage] = useState(0);
  const [qty, setQty] = useState(1);
  const [busy, setBusy] = useState(false);

  // Tồn kho KHÔNG nằm trong Product Service: đọc từ Inventory Service cho đúng các SKU của sản phẩm này, một request.
  const loadStock = useCallback(async (p: ProductDetail) => {
    if (p.skus.length === 0) return new Map<string, Stock>();
    const ids = p.skus.map((s) => `ids=${encodeURIComponent(s.id)}`).join("&");
    const r = await gateway<Stock[]>(`/api/inventory/batch?${ids}`);
    const map = new Map((r.ok && r.data ? r.data : []).map((s) => [s.skuId, s] as const));
    setStock(map);
    return map;
  }, []);

  useEffect(() => {
    let alive = true;
    (async () => {
      const r = await gateway<ProductDetail>(`/api/products/${encodeURIComponent(slug)}`);
      if (!alive) return;
      if (!r.ok || !r.data) {
        setError(r.error);
        return;
      }
      setProduct(r.data);
      const map = await loadStock(r.data);
      if (alive) setSelection((current) => current ?? defaultSelection(r.data!.skus, map));
    })();
    return () => {
      alive = false;
    };
  }, [slug, loadStock]);

  const sku = useMemo(
    () => (product && selection ? findSku(product.skus, product.options, selection) : null),
    [product, selection],
  );

  if (error) {
    return (
      <div className="space-y-3">
        <ErrorBox error={error} />
        <Link href="/" className="btn">← Về cửa hàng</Link>
      </div>
    );
  }
  if (!product || !selection) return <p className="muted">Đang tải…</p>;

  const available = sku ? (stock.get(sku.id)?.available ?? 0) : 0;
  const prices = product.skus.map((s) => s.priceVnd);
  // Ảnh riêng của biến thể (nếu có) được ưu tiên hiển thị khi người dùng chọn biến thể đó.
  const skuImageIndex = sku?.imageId ? product.images.findIndex((i) => i.id === sku.imageId) : -1;
  const shownIndex = skuImageIndex >= 0 && activeImage === -1 ? skuImageIndex : Math.max(activeImage, 0);
  const shown = product.images[shownIndex] ?? null;

  function choose(option: string, value: string) {
    setSelection((s) => ({ ...(s ?? {}), [option]: value }));
    setActiveImage(-1); // nhường ảnh cho biến thể vừa chọn
    setQty(1);
  }

  async function addToCart() {
    if (!sku) return;
    if (!session) {
      toast("Hãy đăng nhập trước khi thêm vào giỏ", "bad");
      return;
    }
    setBusy(true);
    const r = await gateway("/api/cart/items", { method: "POST", token: session.token, body: { skuId: sku.id, quantity: qty } });
    setBusy(false);
    if (r.ok) {
      toast(`Đã thêm ${qty} × ${product!.name}${sku.variantName ? ` (${sku.variantName})` : ""}`, "ok");
      notifyDataChanged();
    } else {
      toast(`${r.error?.code}: ${r.error?.message}`, "bad");
    }
  }

  return (
    <div className="space-y-8">
      <nav className="text-sm text-muted">
        <Link href="/" className="hover:text-text">Cửa hàng</Link> / <span className="text-text">{product.name}</span>
      </nav>

      <div className="grid gap-8 md:grid-cols-2">
        <section className="space-y-3">
          <ProductImage src={shown?.url} alt={shown?.altText || product.name} priority sizes="(min-width: 768px) 50vw, 100vw" />
          {product.images.length > 1 && (
            <div className="grid grid-cols-5 gap-2">
              {product.images.map((img, i) => (
                <button
                  key={img.id}
                  onClick={() => setActiveImage(i)}
                  className={`rounded-lg ring-2 ring-offset-2 ring-offset-bg ${i === shownIndex ? "ring-accent" : "ring-transparent"}`}
                  aria-label={`Xem ảnh ${i + 1}`}
                >
                  <ProductImage src={img.url} alt={img.altText} sizes="10vw" />
                </button>
              ))}
            </div>
          )}
        </section>

        <section className="space-y-5">
          <div className="space-y-1">
            {product.brand && <p className="text-xs uppercase tracking-wide text-muted">{product.brand}</p>}
            <h1 className="h1">{product.name}</h1>
            <a href="#reviews"><RatingLine avg={product.ratingAvg} count={product.ratingCount} /></a>
            {product.shortDescription && <p className="muted pt-1">{product.shortDescription}</p>}
          </div>

          <div className="flex items-baseline gap-3">
            <span className="text-3xl font-semibold">
              {sku
                ? formatVnd(sku.priceVnd)
                : prices.length > 0
                  ? formatPriceRange(Math.min(...prices), Math.max(...prices))
                  : "Chưa có giá"}
            </span>
            {sku?.compareAtPriceVnd && (
              <>
                <span className="text-muted line-through">{formatVnd(sku.compareAtPriceVnd)}</span>
                <span className="badge bg-bad-soft text-bad">
                  −{Math.round((1 - sku.priceVnd / sku.compareAtPriceVnd) * 100)}%
                </span>
              </>
            )}
          </div>

          {product.options.map((o) => (
            <div key={o.name}>
              <p className="label">
                {o.name}: <span className="normal-case text-text">{selection[o.name] ?? "chọn"}</span>
              </p>
              <div className="flex flex-wrap gap-2">
                {o.values.map((v) => {
                  const state = valueState(product.skus, product.options, selection, o.name, v, stock);
                  const selected = selection[o.name] === v;
                  return (
                    <button
                      key={v}
                      disabled={state === "none"}
                      onClick={() => choose(o.name, v)}
                      title={state === "soldout" ? "Hết hàng" : state === "none" ? "Không có biến thể này" : undefined}
                      className={`btn btn-sm min-w-12 ${selected ? "border-accent bg-accent-soft text-accent" : ""} ${state === "soldout" ? "line-through decoration-muted" : ""}`}
                    >
                      {v}
                    </button>
                  );
                })}
              </div>
            </div>
          ))}

          <div className="flex flex-wrap items-center gap-2 text-xs">
            {sku ? (
              <>
                <span className={`badge ${available > 0 ? "bg-ok-soft text-ok" : "bg-bad-soft text-bad"}`}>
                  {available > 0 ? `Còn ${available} sản phẩm` : "Hết hàng"}
                </span>
                <span className="badge bg-surface-2 text-muted">SKU {sku.skuCode}</span>
              </>
            ) : (
              <span className="badge bg-warn-soft text-warn">Chọn đủ phân loại để xem tồn kho</span>
            )}
          </div>
          {sku && SKU_HINTS[sku.id] && <p className="text-xs italic text-muted">{SKU_HINTS[sku.id]}</p>}

          <div className="flex items-center gap-2">
            <input
              aria-label="Số lượng"
              type="number"
              min={1}
              max={99}
              className="input w-20"
              value={qty}
              onChange={(e) => setQty(Math.max(1, Math.min(99, Number(e.target.value) || 1)))}
            />
            <button className="btn btn-primary flex-1" disabled={!sku || available <= 0 || busy} onClick={addToCart}>
              {busy ? "Đang thêm…" : "Thêm vào giỏ"}
            </button>
          </div>
          <p className="text-xs text-muted">
            Tồn kho hiển thị là số thật của Inventory Service lúc tải trang. Kho chỉ thật sự được giữ khi bạn đặt hàng
            (Saga) — người khác có thể mua mất trước.
          </p>
        </section>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
        <section className="card">
          <h2 className="h2 mb-3">Mô tả sản phẩm</h2>
          {product.description ? <Markdown>{product.description}</Markdown> : <p className="muted">Chưa có mô tả.</p>}
        </section>
        {product.specifications.length > 0 && (
          <section className="card h-fit">
            <h2 className="h2 mb-3">Thông số</h2>
            <table className="table">
              <tbody>
                {product.specifications.map((s) => (
                  <tr key={s.name}>
                    <td className="text-muted">{s.name}</td>
                    <td className="font-medium">{s.value}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
      </div>

      <Reviews productId={product.id} />
    </div>
  );
}
