"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { gateway } from "@/lib/api";
import { useSession } from "@/lib/session";
import type { Page, ProductSummary, Review } from "@/lib/types";
import { SystemOverview } from "./operations/OperationsView";

type Counts = { products: number | null; drafts: number | null; reviews: number | null; hidden: number | null };

export default function DashboardPage() {
  const admin = useSession("admin");
  const [counts, setCounts] = useState<Counts>({ products: null, drafts: null, reviews: null, hidden: null });

  useEffect(() => {
    if (!admin) return;
    const token = admin.token;
    const total = async (path: string) => (await gateway<Page<ProductSummary | Review>>(path, { token })).data?.totalElements ?? null;
    void (async () => {
      const [products, drafts, reviews, hidden] = await Promise.all([
        total("/api/products/manage?size=1"),
        total("/api/products/manage?size=1&active=false"),
        total("/api/reviews/moderation?size=1"),
        total("/api/reviews/moderation?size=1&status=HIDDEN"),
      ]);
      setCounts({ products, drafts, reviews, hidden });
    })();
  }, [admin]);

  if (!admin) return null;
  const tiles = [
    { label: "Sản phẩm (SPU)", value: counts.products, href: "/products" },
    { label: "Bản nháp / ngừng bán", value: counts.drafts, href: "/products?active=false" },
    { label: "Đánh giá", value: counts.reviews, href: "/reviews" },
    { label: "Đánh giá đã ẩn", value: counts.hidden, href: "/reviews?status=HIDDEN" },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="h1">Tổng quan</h1>
          <p className="muted">Catalog, đánh giá và sức khỏe hệ thống.</p>
        </div>
        <Link href="/products/new" className="btn btn-primary">+ Thêm sản phẩm</Link>
      </div>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {tiles.map((t) => (
          <Link key={t.label} href={t.href} className="card transition hover:border-accent/50">
            <p className="label">{t.label}</p>
            <p className="text-2xl font-semibold tabular-nums">{t.value ?? "—"}</p>
          </Link>
        ))}
      </div>
      <SystemOverview token={admin.token} />
    </div>
  );
}
