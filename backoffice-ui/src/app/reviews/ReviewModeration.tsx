"use client";

import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { gateway } from "@/lib/api";
import { useSession } from "@/lib/session";
import { formatDateTime, shortId, STOREFRONT_URL } from "@/lib/format";
import type { ApiError, Page, Review } from "@/lib/types";
import { ErrorBox } from "@/components/ErrorBox";
import { toast } from "@/components/Toast";

const PAGE_SIZE = 20;

/**
 * Kiểm duyệt đánh giá (review-service). Ẩn/hiện → review-service tính lại điểm trung bình trong cùng transaction và
 * phát ProductRatingChanged (Outbox) → Product Service cập nhật bản sao rating → search-service (CDC) index lại.
 */
export function ReviewModeration() {
  const admin = useSession("admin");
  const params = useSearchParams();
  const [status, setStatus] = useState(params.get("status") ?? "");
  const [productId, setProductId] = useState("");
  const [pageNo, setPageNo] = useState(0);
  const [page, setPage] = useState<Page<Review> | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const load = useCallback(async () => {
    if (!admin) return;
    const q = new URLSearchParams({ page: String(pageNo), size: String(PAGE_SIZE) });
    if (status) q.set("status", status);
    if (/^[0-9a-f-]{36}$/i.test(productId.trim())) q.set("productId", productId.trim());
    const r = await gateway<Page<Review>>(`/api/reviews/moderation?${q}`, { token: admin.token });
    setPage(r.ok ? r.data : null);
    setError(r.ok ? null : r.error);
  }, [admin, pageNo, status, productId]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải dữ liệu khi bộ lọc đổi
    void load();
  }, [load]);

  async function moderate(review: Review, next: Review["status"]) {
    if (!admin) return;
    const r = await gateway<Review>(`/api/reviews/moderation/${review.id}/status`, {
      method: "PUT",
      token: admin.token,
      body: { status: next },
    });
    if (r.ok) {
      toast(next === "HIDDEN" ? "Đã ẩn đánh giá — điểm trung bình được tính lại" : "Đã hiện lại đánh giá", "ok");
      void load();
    } else {
      toast(`${r.error?.code}: ${r.error?.message}`, "bad");
    }
  }

  const pages = page ? Math.ceil(page.totalElements / PAGE_SIZE) : 0;

  return (
    <div className="space-y-5">
      <div>
        <h1 className="h1">Đánh giá</h1>
        <p className="muted">Chỉ khách đã có đơn thành công mới viết được đánh giá. Bài bị ẩn không hiển thị và không tính điểm.</p>
      </div>

      <div className="card flex flex-wrap items-end gap-3">
        <div>
          <label className="label" htmlFor="status">Trạng thái</label>
          <select id="status" className="input" value={status} onChange={(e) => { setStatus(e.target.value); setPageNo(0); }}>
            <option value="">Tất cả</option>
            <option value="PUBLISHED">Đang hiển thị</option>
            <option value="HIDDEN">Đã ẩn</option>
          </select>
        </div>
        <div className="min-w-80 flex-1">
          <label className="label" htmlFor="pid">Lọc theo productId (SPU)</label>
          <input id="pid" className="input mono" placeholder="UUID của sản phẩm" value={productId}
                 onChange={(e) => { setProductId(e.target.value); setPageNo(0); }} />
        </div>
      </div>

      <ErrorBox error={error} />
      <div className="space-y-3">
        {page?.items.map((r) => (
          <article key={r.id} className={`card space-y-2 ${r.status === "HIDDEN" ? "opacity-70" : ""}`}>
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <span className="text-warn">{"★".repeat(r.rating)}<span className="text-border">{"★".repeat(5 - r.rating)}</span></span>
              <span className="font-medium">{r.authorName}</span>
              <span className="text-xs text-muted">{formatDateTime(r.createdAt)}</span>
              {r.variantName && <span className="badge bg-surface-2 text-muted">{r.variantName}</span>}
              <span className={`badge ${r.status === "PUBLISHED" ? "bg-ok-soft text-ok" : "bg-warn-soft text-warn"}`}>
                {r.status === "PUBLISHED" ? "Đang hiển thị" : "Đã ẩn"}
              </span>
              <a className="mono text-xs text-accent underline" href={`${STOREFRONT_URL}/products/${r.productId}#reviews`}
                 target="_blank" rel="noreferrer">
                SPU {shortId(r.productId)} ↗
              </a>
              <div className="ml-auto">
                {r.status === "PUBLISHED" ? (
                  <button className="btn btn-sm" onClick={() => void moderate(r, "HIDDEN")}>Ẩn</button>
                ) : (
                  <button className="btn btn-sm" onClick={() => void moderate(r, "PUBLISHED")}>Hiện lại</button>
                )}
              </div>
            </div>
            {r.title && <p className="font-medium">{r.title}</p>}
            <p className="text-sm whitespace-pre-line">{r.content}</p>
          </article>
        ))}
        {page?.items.length === 0 && <p className="muted">Không có đánh giá phù hợp.</p>}
      </div>
      {pages > 1 && (
        <div className="flex items-center gap-2 text-sm">
          <button className="btn btn-sm" disabled={pageNo === 0} onClick={() => setPageNo(pageNo - 1)}>← Trước</button>
          <span className="text-muted">Trang {pageNo + 1}/{pages}</span>
          <button className="btn btn-sm" disabled={pageNo + 1 >= pages} onClick={() => setPageNo(pageNo + 1)}>Sau →</button>
        </div>
      )}
    </div>
  );
}
