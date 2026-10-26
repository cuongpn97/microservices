"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { gateway } from "@/lib/api";
import { useSession } from "@/lib/session";
import type { Page, ProductSummary, SearchResult } from "@/lib/types";
import { ProductCard, type CardProduct } from "@/components/ProductCard";

type Sort = "RELEVANCE" | "PRICE_ASC" | "PRICE_DESC" | "RATING" | "NAME";

const SORTS: { value: Sort; label: string }[] = [
  { value: "RELEVANCE", label: "Liên quan" },
  { value: "PRICE_ASC", label: "Giá tăng dần" },
  { value: "PRICE_DESC", label: "Giá giảm dần" },
  { value: "RATING", label: "Đánh giá cao" },
  { value: "NAME", label: "Tên A–Z" },
];

export default function ShopPage() {
  const session = useSession();
  const [products, setProducts] = useState<CardProduct[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [sort, setSort] = useState<Sort>("RELEVANCE");
  const [minPrice, setMinPrice] = useState("");
  const [maxPrice, setMaxPrice] = useState("");
  const [searchInfo, setSearchInfo] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(() => setDebouncedQuery(query.trim()), 300);
    return () => clearTimeout(t);
  }, [query]);

  // Có từ khóa/bộ lọc/sắp xếp → search-service (Elasticsearch, dựng từ CDC — chạy khi bật profile "search").
  // Không có → danh sách trực tiếp từ Product Service (luôn chạy, dữ liệu mới nhất).
  const usesSearch = debouncedQuery !== "" || sort !== "RELEVANCE" || minPrice !== "" || maxPrice !== "";

  const load = useCallback(async () => {
    if (usesSearch) {
      const params = new URLSearchParams({ size: "48", sort });
      if (debouncedQuery) params.set("q", debouncedQuery);
      if (minPrice) params.set("minPriceVnd", minPrice);
      if (maxPrice) params.set("maxPriceVnd", maxPrice);
      const s = await gateway<SearchResult>(`/api/search/products?${params}`);
      if (!s.ok || !s.data) {
        setError(
          s.error?.message
            ? `Tìm kiếm lỗi: ${s.error.message} (search-service chạy bằng: docker compose --profile search up -d)`
            : "Không gọi được search-service",
        );
        return;
      }
      setProducts(s.data.items);
      setSearchInfo(`${s.data.totalElements} sản phẩm · Elasticsearch ${s.data.tookMs} ms`);
    } else {
      const r = await gateway<Page<ProductSummary>>("/api/products?size=48");
      if (!r.ok || !r.data) {
        setError(r.error?.message ?? "Không tải được sản phẩm");
        return;
      }
      setProducts(r.data.items);
      setSearchInfo(null);
    }
    setError(null);
  }, [usesSearch, debouncedQuery, sort, minPrice, maxPrice]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải dữ liệu khi bộ lọc đổi
    void load();
  }, [load]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="h1">Cửa hàng</h1>
          <p className="muted">Mỗi thẻ là một sản phẩm (SPU); chọn màu/size… và xem tồn kho từng biến thể (SKU) ở trang chi tiết.</p>
        </div>
        {!session && (
          <Link href="/login" className="btn btn-primary">
            Đăng ký để nhận 10.000.000 ₫ credit
          </Link>
        )}
      </div>

      <div className="card flex flex-wrap items-end gap-3">
        <div className="min-w-56 flex-1">
          <label className="label" htmlFor="q">Tìm kiếm</label>
          <input
            id="q"
            type="search"
            className="input"
            placeholder="Gõ không dấu cũng được: ao thun den, ban phim, KB87-RED…"
            value={query}
            maxLength={200}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <div>
          <label className="label" htmlFor="sort">Sắp xếp</label>
          <select id="sort" className="input" value={sort} onChange={(e) => setSort(e.target.value as Sort)}>
            {SORTS.map((s) => (
              <option key={s.value} value={s.value}>{s.label}</option>
            ))}
          </select>
        </div>
        <div className="flex items-end gap-1">
          <div>
            <label className="label" htmlFor="min">Giá từ</label>
            <input id="min" type="number" min={0} step={10000} className="input w-32" value={minPrice}
                   onChange={(e) => setMinPrice(e.target.value)} />
          </div>
          <span className="pb-2 text-muted">–</span>
          <div>
            <label className="label" htmlFor="max">đến</label>
            <input id="max" type="number" min={0} step={10000} className="input w-32" value={maxPrice}
                   onChange={(e) => setMaxPrice(e.target.value)} />
          </div>
        </div>
        {usesSearch && (
          <button className="btn" onClick={() => { setQuery(""); setSort("RELEVANCE"); setMinPrice(""); setMaxPrice(""); }}>
            Xóa lọc
          </button>
        )}
        {searchInfo && <p className="w-full text-xs text-muted">{searchInfo} · kết quả có thể trễ vài giây so với Product Service (CDC)</p>}
      </div>

      {error && <div className="card text-bad">{error} — kiểm tra docker compose đã chạy chưa.</div>}
      {products && products.length === 0 && <div className="muted">Không có sản phẩm phù hợp.</div>}
      {!products && !error && <div className="muted">Đang tải…</div>}

      <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-4">
        {products?.map((p) => <ProductCard key={p.slug} product={p} />)}
      </div>
    </div>
  );
}
