"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { gateway } from "@/lib/api";
import { notifyDataChanged, useSession } from "@/lib/session";
import { formatVnd, PRODUCT_HINTS } from "@/lib/format";
import type { Product, SearchResult, Stock } from "@/lib/types";
import { toast } from "@/components/Toast";

type Row = Product & { stock: Stock | null };

export default function ShopPage() {
  const session = useSession();
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [qty, setQty] = useState<Record<string, number>>({});
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [searchInfo, setSearchInfo] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(() => setDebouncedQuery(query.trim()), 300);
    return () => clearTimeout(t);
  }, [query]);

  const load = useCallback(async () => {
    let products: Product[];
    if (debouncedQuery) {
      // Tìm kiếm đi qua search-service (Elasticsearch, dựng từ CDC) — chạy khi bật profile "search".
      const params = new URLSearchParams({ q: debouncedQuery, size: "50" });
      const s = await gateway<SearchResult>(`/api/search/products?${params}`);
      if (!s.ok || !s.data) {
        setError(
          s.error?.message
            ? `Tìm kiếm lỗi: ${s.error.message} (search-service chạy bằng: docker compose --profile search up -d)`
            : "Không gọi được search-service",
        );
        return;
      }
      products = s.data.items.map((h) => ({
        id: h.productId,
        name: h.name,
        description: h.description,
        priceVnd: h.priceVnd,
        active: true,
      }));
      setSearchInfo(`${s.data.totalElements} kết quả · Elasticsearch ${s.data.tookMs} ms`);
    } else {
      const r = await gateway<{ items: Product[] }>("/api/products?size=50");
      if (!r.ok || !r.data) {
        setError(r.error?.message ?? "Không tải được sản phẩm");
        return;
      }
      products = r.data.items;
      setSearchInfo(null);
    }
    // Tồn kho KHÔNG nằm trong index tìm kiếm (thay đổi quá thường xuyên): đọc số thật từ Inventory Service cho đúng
    // các sản phẩm đang hiển thị — một request batch thay vì một request mỗi sản phẩm (N+1 qua HTTP).
    const stockById = new Map<string, Stock>();
    if (products.length > 0) {
      const ids = products.map((p) => `ids=${encodeURIComponent(p.id)}`).join("&");
      const s = await gateway<Stock[]>(`/api/inventory/batch?${ids}`);
      for (const stock of s.ok && s.data ? s.data : []) stockById.set(stock.productId, stock);
    }
    setRows(products.map((p) => ({ ...p, stock: stockById.get(p.id) ?? null })));
    setError(null);
  }, [debouncedQuery]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải dữ liệu lần đầu
    void load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [load]);

  async function add(product: Row) {
    if (!session) {
      toast("Hãy đăng nhập trước khi thêm vào giỏ", "bad");
      return;
    }
    const quantity = qty[product.id] ?? 1;
    const r = await gateway("/api/cart/items", {
      method: "POST",
      token: session.token,
      body: { productId: product.id, quantity },
    });
    if (r.ok) {
      toast(`Đã thêm ${quantity} × ${product.name}`, "ok");
      notifyDataChanged();
    } else {
      toast(`${r.error?.code}: ${r.error?.message}`, "bad");
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="h1">Cửa hàng</h1>
          <p className="muted">
            Giá do Product Service quản lý; tồn kho do Inventory Service quản lý (hai database khác nhau, ghép ở phía client).
          </p>
        </div>
        {!session && (
          <Link href="/login" className="btn btn-primary">
            Đăng ký để nhận 10.000.000 ₫ credit
          </Link>
        )}
      </div>

      <div className="card flex flex-wrap items-center gap-3">
        <input
          aria-label="Tìm sản phẩm"
          type="search"
          className="input min-w-0 flex-1"
          placeholder="Tìm sản phẩm — gõ không dấu cũng được, ví dụ: ban phim"
          value={query}
          maxLength={200}
          onChange={(e) => setQuery(e.target.value)}
        />
        {searchInfo && <span className="muted text-xs">{searchInfo} · kết quả có thể trễ vài giây (CDC), tồn kho là số thật</span>}
      </div>

      {error && <div className="card text-bad">{error} — kiểm tra docker compose đã chạy chưa.</div>}
      {rows && rows.length === 0 && <div className="muted">Không có sản phẩm phù hợp.</div>}
      {!rows && !error && <div className="muted">Đang tải…</div>}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {rows?.map((p) => {
          const available = p.stock?.available ?? 0;
          return (
            <div key={p.id} className="card flex flex-col gap-3">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <h2 className="font-semibold">{p.name}</h2>
                  <p className="muted">{p.description}</p>
                </div>
                <span className="text-right text-lg font-semibold whitespace-nowrap">{formatVnd(p.priceVnd)}</span>
              </div>
              <div className="flex flex-wrap gap-2 text-xs">
                <span className={`badge ${available > 0 ? "bg-ok-soft text-ok" : "bg-bad-soft text-bad"}`}>
                  Còn {available}
                </span>
                {p.stock && p.stock.reservedQuantity > 0 && (
                  <span className="badge bg-warn-soft text-warn">Đang giữ {p.stock.reservedQuantity}</span>
                )}
                {p.stock && <span className="badge bg-surface-2 text-muted">Tồn kho {p.stock.stockOnHand}</span>}
              </div>
              {PRODUCT_HINTS[p.id] && <p className="text-xs text-muted italic">{PRODUCT_HINTS[p.id]}</p>}
              <div className="mt-auto flex items-center gap-2">
                <input
                  aria-label="Số lượng"
                  type="number"
                  min={1}
                  max={99}
                  className="input w-20"
                  value={qty[p.id] ?? 1}
                  onChange={(e) => setQty({ ...qty, [p.id]: Number(e.target.value) })}
                />
                <button className="btn btn-primary flex-1" onClick={() => add(p)}>
                  Thêm vào giỏ
                </button>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
