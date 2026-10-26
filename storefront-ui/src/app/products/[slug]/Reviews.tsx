"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { gateway } from "@/lib/api";
import { useSession } from "@/lib/session";
import { formatDate } from "@/lib/format";
import type { ApiError, MyReview, Page, RatingSummary, Review } from "@/lib/types";
import { ErrorBox } from "@/components/ErrorBox";
import { Stars } from "@/components/Stars";
import { toast } from "@/components/Toast";

const PAGE_SIZE = 5;

/**
 * Đánh giá lấy thẳng từ review-service (không qua Product Service). Tóm tắt rating cũng có ở Product Service (bản sao
 * nhận qua event) — ở đây đọc bản gốc để có histogram và cập nhật ngay sau khi người dùng viết đánh giá.
 */
export function Reviews({ productId }: { productId: string }) {
  const session = useSession();
  const [summary, setSummary] = useState<RatingSummary | null>(null);
  const [page, setPage] = useState<Page<Review> | null>(null);
  const [filter, setFilter] = useState<number | null>(null);
  const [pageNo, setPageNo] = useState(0);
  const [mine, setMine] = useState<MyReview | null>(null);
  const [unavailable, setUnavailable] = useState(false);

  const load = useCallback(async () => {
    const params = new URLSearchParams({ page: String(pageNo), size: String(PAGE_SIZE) });
    if (filter) params.set("rating", String(filter));
    const [s, l] = await Promise.all([
      gateway<RatingSummary>(`/api/reviews/products/${productId}/summary`),
      gateway<Page<Review>>(`/api/reviews/products/${productId}?${params}`),
    ]);
    setUnavailable(!s.ok);
    if (s.ok) setSummary(s.data);
    if (l.ok) setPage(l.data);
    if (session) {
      const m = await gateway<MyReview>(`/api/reviews/products/${productId}/mine`, { token: session.token });
      setMine(m.ok ? m.data : null);
    } else {
      setMine(null);
    }
  }, [productId, pageNo, filter, session]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải dữ liệu khi bộ lọc/phiên đổi
    void load();
  }, [load]);

  if (unavailable) {
    return (
      <section id="reviews" className="card">
        <h2 className="h2">Đánh giá</h2>
        <p className="muted">Review Service tạm thời không phản hồi — phần còn lại của trang vẫn dùng được.</p>
      </section>
    );
  }

  const total = summary?.ratingCount ?? 0;
  const pages = page ? Math.ceil(page.totalElements / PAGE_SIZE) : 0;

  return (
    <section id="reviews" className="card space-y-5">
      <h2 className="h2">Đánh giá sản phẩm</h2>

      <div className="grid gap-6 md:grid-cols-[220px_1fr]">
        <div className="space-y-1">
          <div className="text-4xl font-semibold">{summary?.ratingAvg?.toFixed(1) ?? "—"}<span className="text-base text-muted">/5</span></div>
          <Stars value={summary?.ratingAvg ?? null} size="text-xl" />
          <p className="text-xs text-muted">{total} đánh giá từ người đã mua</p>
        </div>
        <div className="space-y-1">
          {[5, 4, 3, 2, 1].map((stars) => {
            const n = summary?.histogram[String(stars)] ?? 0;
            const active = filter === stars;
            return (
              <button
                key={stars}
                onClick={() => {
                  setFilter(active ? null : stars);
                  setPageNo(0);
                }}
                className={`flex w-full items-center gap-2 rounded px-1 text-xs hover:bg-surface-2 ${active ? "bg-accent-soft" : ""}`}
              >
                <span className="w-10 text-left">{stars} sao</span>
                <span className="h-2 flex-1 overflow-hidden rounded-full bg-surface-2">
                  <span className="block h-full bg-warn" style={{ width: `${total ? (n / total) * 100 : 0}%` }} />
                </span>
                <span className="w-8 text-right tabular-nums text-muted">{n}</span>
              </button>
            );
          })}
        </div>
      </div>

      <MyReviewBox productId={productId} mine={mine} loggedIn={!!session} token={session?.token} onSaved={load} />

      <div className="space-y-4">
        {filter && (
          <p className="text-xs text-muted">
            Đang lọc {filter} sao · <button className="underline" onClick={() => setFilter(null)}>bỏ lọc</button>
          </p>
        )}
        {page?.items.length === 0 && <p className="muted">Chưa có đánh giá nào{filter ? " ở mức này" : ""}.</p>}
        {page?.items.map((r) => (
          <article key={r.id} className="space-y-1 border-t border-border pt-4 first:border-t-0 first:pt-0">
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <span className="font-medium">{r.authorName}</span>
              <Stars value={r.rating} />
              <span className="text-xs text-muted">{formatDate(r.createdAt)}</span>
              {r.variantName && <span className="badge bg-surface-2 text-muted">Phân loại: {r.variantName}</span>}
              <span className="badge bg-ok-soft text-ok">Đã mua hàng</span>
            </div>
            {r.title && <p className="font-medium">{r.title}</p>}
            <p className="text-sm whitespace-pre-line">{r.content}</p>
          </article>
        ))}
        {pages > 1 && (
          <div className="flex items-center gap-2 text-sm">
            <button className="btn btn-sm" disabled={pageNo === 0} onClick={() => setPageNo(pageNo - 1)}>← Trước</button>
            <span className="text-muted">Trang {pageNo + 1}/{pages}</span>
            <button className="btn btn-sm" disabled={pageNo + 1 >= pages} onClick={() => setPageNo(pageNo + 1)}>Sau →</button>
          </div>
        )}
      </div>
    </section>
  );
}

function MyReviewBox({
  productId,
  mine,
  loggedIn,
  token,
  onSaved,
}: {
  productId: string;
  mine: MyReview | null;
  loggedIn: boolean;
  token?: string;
  onSaved: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [rating, setRating] = useState(5);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  if (!loggedIn) {
    return (
      <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm text-muted">
        <Link className="text-accent underline" href="/login">Đăng nhập</Link> để viết đánh giá (chỉ dành cho người đã mua).
      </p>
    );
  }
  if (!mine) return null;
  if (!mine.canReview) {
    return (
      <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm text-muted">
        Chỉ khách đã có đơn hàng <b>thành công</b> chứa sản phẩm này mới viết được đánh giá. Vừa đặt xong? Review Service
        ghi nhận lượt mua từ event <code className="mono">OrderConfirmed</code> — có thể trễ vài giây.
      </p>
    );
  }

  function startEdit() {
    setRating(mine!.review?.rating ?? 5);
    setTitle(mine!.review?.title ?? "");
    setContent(mine!.review?.content ?? "");
    setError(null);
    setEditing(true);
  }

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    const r = await gateway<Review>(`/api/reviews/products/${productId}/mine`, {
      method: "PUT",
      token,
      body: { rating, title, content },
    });
    setBusy(false);
    if (!r.ok) {
      setError(r.error);
      return;
    }
    toast("Đã lưu đánh giá — điểm trung bình được cập nhật", "ok");
    setEditing(false);
    onSaved();
  }

  if (!editing) {
    return (
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-accent-soft px-3 py-2 text-sm">
        {mine.review ? (
          <span>
            Bạn đã đánh giá <b>{mine.review.rating} sao</b>
            {mine.review.status === "HIDDEN" && (
              <span className="badge ml-2 bg-warn-soft text-warn">Đã bị ẩn bởi quản trị viên</span>
            )}
          </span>
        ) : (
          <span>Bạn đã mua sản phẩm này — chia sẻ trải nghiệm nhé!</span>
        )}
        <button className="btn btn-primary btn-sm" onClick={startEdit}>
          {mine.review ? "Sửa đánh giá" : "Viết đánh giá"}
        </button>
      </div>
    );
  }

  return (
    <form onSubmit={save} className="space-y-3 rounded-lg border border-border p-4">
      <div>
        <p className="label">Chấm điểm</p>
        <div className="flex gap-1 text-2xl">
          {[1, 2, 3, 4, 5].map((i) => (
            <button
              key={i}
              type="button"
              aria-label={`${i} sao`}
              onClick={() => setRating(i)}
              className={i <= rating ? "text-warn" : "text-border"}
            >
              ★
            </button>
          ))}
        </div>
      </div>
      <div>
        <label className="label" htmlFor="rv-title">Tiêu đề (không bắt buộc)</label>
        <input id="rv-title" className="input" maxLength={150} value={title} onChange={(e) => setTitle(e.target.value)} />
      </div>
      <div>
        <label className="label" htmlFor="rv-content">Nội dung (≥ 10 ký tự)</label>
        <textarea
          id="rv-content"
          className="input min-h-28"
          minLength={10}
          maxLength={4000}
          required
          value={content}
          onChange={(e) => setContent(e.target.value)}
        />
      </div>
      <ErrorBox error={error} />
      <div className="flex gap-2">
        <button className="btn btn-primary" disabled={busy}>{busy ? "Đang lưu…" : "Gửi đánh giá"}</button>
        <button type="button" className="btn" onClick={() => setEditing(false)}>Hủy</button>
      </div>
    </form>
  );
}
