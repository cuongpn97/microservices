"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useSession } from "@/lib/session";
import { DetailsForm } from "../DetailsForm";

export default function NewProductPage() {
  const admin = useSession("admin");
  const router = useRouter();
  if (!admin) return null;
  return (
    <div className="space-y-5">
      <nav className="text-sm text-muted">
        <Link href="/products" className="hover:text-text">Sản phẩm</Link> / <span className="text-text">Thêm mới</span>
      </nav>
      <DetailsForm product={null} token={admin.token} onSaved={(p) => router.push(`/products/${p.id}`)} />
    </div>
  );
}
