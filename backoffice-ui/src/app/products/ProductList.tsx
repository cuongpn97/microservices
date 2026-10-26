"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { gateway } from "@/lib/api";
import { useSession } from "@/lib/session";
import { formatDateTime, formatPriceRange } from "@/lib/format";
import type { ApiError, Page, ProductSummary } from "@/lib/types";
import { ErrorBox } from "@/components/ErrorBox";
import { ProductImage } from "@/components/ProductImage";

const PAGE_SIZE = 20;

export function ProductList() {
  const admin = useSession("admin");
  const params = useSearchParams();
  const [q, setQ] = useState("");
  const [debounced, setDebounced] = useState("");
  const [active, setActive] = useState<string>(params.get("active") ?? "");
  const [pageNo, setPageNo] = useState(0);
  const [page, setPage] = useState<Page<ProductSummary> | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(q.trim());
      setPageNo(0);
    }, 300);
    return () => clearTimeout(t);
  }, [q]);

  const load = useCallback(async () => {
    if (!admin) return;
    const query = new URLSearchParams({ page: String(pageNo), size: String(PAGE_SIZE) });
    if (debounced) query.set("q", debounced);
    if (active) query.set("active", active);
    const r = await gateway<Page<ProductSummary>>(`/api/products/manage?${query}`, { token: admin.token });
    setPage(r.ok ? r.data : null);
    setError(r.ok ? null : r.error);
  }, [admin, pageNo, debounced, active]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải dữ liệu khi bộ lọc đổi
    void load();
  }, [load]);

  const pages = page ? Math.ceil(page.totalElements / PAGE_SIZE) : 0;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="h1">Sản phẩm</h1>
          <p className="muted">Mỗi dòng là một SPU; giá là khoảng min–max của các SKU đang bật.</p>
        </div>
        <Link href="/products/new" className="btn btn-primary">+ Thêm sản phẩm</Link>
      </div>

      <div className="card flex flex-wrap items-end gap-3">
        <div className="min-w-56 flex-1">
          <label className="label" htmlFor="q">Tìm theo tên / slug</label>
          <input id="q" type="search" className="input" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div>
          <label className="label" htmlFor="active">Trạng thái</label>
          <select id="active" className="input" value={active} onChange={(e) => { setActive(e.target.value); setPageNo(0); }}>
            <option value="">Tất cả</option>
            <option value="true">Đang bán</option>
            <option value="false">Nháp / ngừng bán</option>
          </select>
        </div>
      </div>

      <ErrorBox error={error} />
      <div className="card overflow-x-auto">
        <table className="table">
          <thead>
            <tr>
              <th>Sản phẩm</th>
              <th>Thương hiệu</th>
              <th className="text-right">Giá</th>
              <th className="text-center">SKU</th>
              <th>Trạng thái</th>
              <th>Cập nhật</th>
            </tr>
          </thead>
          <tbody>
            {page?.items.map((p) => (
              <tr key={p.id}>
                <td>
                  <Link href={`/products/${p.id}`} className="flex items-center gap-3 hover:text-accent">
                    <ProductImage src={p.imageUrl} alt={p.name} className="w-12 shrink-0" sizes="48px" />
                    <span>
                      <span className="block font-medium">{p.name}</span>
                      <span className="mono text-muted">{p.slug}</span>
                    </span>
                  </Link>
                </td>
                <td>{p.brand ?? "—"}</td>
                <td className="text-right whitespace-nowrap">{formatPriceRange(p.minPriceVnd, p.maxPriceVnd)}</td>
                <td className="text-center tabular-nums">{p.skuCount}</td>
                <td>
                  <span className={`badge ${p.active ? "bg-ok-soft text-ok" : "bg-surface-2 text-muted"}`}>
                    {p.active ? "Đang bán" : "Nháp"}
                  </span>
                </td>
                <td className="whitespace-nowrap text-muted">{formatDateTime(p.updatedAt)}</td>
              </tr>
            ))}
            {page?.items.length === 0 && (
              <tr><td colSpan={6} className="muted">Không có sản phẩm phù hợp.</td></tr>
            )}
          </tbody>
        </table>
        {pages > 1 && (
          <div className="mt-3 flex items-center gap-2 text-sm">
            <button className="btn btn-sm" disabled={pageNo === 0} onClick={() => setPageNo(pageNo - 1)}>← Trước</button>
            <span className="text-muted">Trang {pageNo + 1}/{pages} · {page?.totalElements} sản phẩm</span>
            <button className="btn btn-sm" disabled={pageNo + 1 >= pages} onClick={() => setPageNo(pageNo + 1)}>Sau →</button>
          </div>
        )}
      </div>
    </div>
  );
}
