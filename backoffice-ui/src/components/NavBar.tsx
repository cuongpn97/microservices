"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { saveSession, useSession } from "@/lib/session";
import { STOREFRONT_URL, SWAGGER_URL } from "@/lib/format";

const LINKS = [
  { href: "/", label: "Tổng quan" },
  { href: "/products", label: "Sản phẩm" },
  { href: "/reviews", label: "Đánh giá" },
  { href: "/operations", label: "Vận hành" },
];

export function NavBar() {
  const pathname = usePathname();
  const admin = useSession("admin");

  return (
    <header className="sticky top-0 z-40 border-b border-border bg-surface/90 backdrop-blur">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3 sm:px-6">
        <Link href="/" className="flex items-center gap-2 font-semibold">
          <span className="grid h-7 w-7 place-items-center rounded-md bg-text text-sm text-bg">B</span>
          Backoffice
        </Link>
        {admin && (
          <nav className="flex flex-wrap gap-1 text-sm">
            {LINKS.map((l) => {
              const active = l.href === "/" ? pathname === "/" : pathname.startsWith(l.href);
              return (
                <Link
                  key={l.href}
                  href={l.href}
                  className={`rounded-md px-2.5 py-1.5 ${active ? "bg-accent-soft text-accent" : "text-muted hover:text-text"}`}
                >
                  {l.label}
                </Link>
              );
            })}
          </nav>
        )}
        <div className="ml-auto flex items-center gap-3 text-sm">
          <a href={STOREFRONT_URL} target="_blank" rel="noreferrer" className="text-muted hover:text-text">Cửa hàng ↗</a>
          <a href={SWAGGER_URL} target="_blank" rel="noreferrer" className="text-muted hover:text-text">Swagger ↗</a>
          {admin && (
            <>
              <span className="hidden max-w-48 truncate text-muted lg:inline">{admin.email}</span>
              <button className="btn btn-sm" onClick={() => saveSession("admin", null)}>Đăng xuất</button>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
