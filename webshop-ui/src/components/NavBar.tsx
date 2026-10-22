"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { saveSession, useSession } from "@/lib/session";
import { useCart, useWallet } from "@/lib/hooks";
import { formatVnd } from "@/lib/format";

const LINKS = [
  { href: "/", label: "Cửa hàng" },
  { href: "/cart", label: "Giỏ hàng" },
  { href: "/orders", label: "Đơn hàng" },
  { href: "/wallet", label: "Ví" },
  { href: "/scenarios", label: "Kịch bản demo" },
  { href: "/admin", label: "Quản trị" },
];

export const SWAGGER_URL = process.env.NEXT_PUBLIC_SWAGGER_URL ?? "http://localhost:8080/swagger-ui.html";

export function NavBar() {
  const pathname = usePathname();
  const router = useRouter();
  const session = useSession();
  const { wallet } = useWallet();
  const { cart } = useCart();
  const count = cart?.items.reduce((sum, i) => sum + i.quantity, 0) ?? 0;

  return (
    <header className="sticky top-0 z-40 border-b border-border bg-surface/90 backdrop-blur">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3 sm:px-6">
        <Link href="/" className="flex items-center gap-2 font-semibold">
          <span className="grid h-7 w-7 place-items-center rounded-md bg-accent text-sm text-white">W</span>
          Webshop
        </Link>
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
                {l.href === "/cart" && count > 0 && (
                  <span className="ml-1.5 rounded-full bg-accent px-1.5 text-xs text-white">{count}</span>
                )}
              </Link>
            );
          })}
          <a href={SWAGGER_URL} target="_blank" rel="noreferrer" className="rounded-md px-2.5 py-1.5 text-muted hover:text-text">
            Swagger ↗
          </a>
        </nav>
        <div className="ml-auto flex items-center gap-3 text-sm">
          {session ? (
            <>
              <Link href="/wallet" className="badge bg-ok-soft text-ok" title="Số dư khả dụng">
                {wallet ? formatVnd(wallet.availableVnd) : "Ví đang khởi tạo…"}
              </Link>
              <span className="hidden max-w-48 truncate text-muted xl:inline">{session.email}</span>
              <button
                className="btn btn-sm"
                onClick={() => {
                  saveSession("user", null);
                  router.push("/login");
                }}
              >
                Đăng xuất
              </button>
            </>
          ) : (
            <Link href="/login" className="btn btn-primary btn-sm">
              Đăng nhập
            </Link>
          )}
        </div>
      </div>
    </header>
  );
}
