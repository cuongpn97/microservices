import { Suspense } from "react";
import Link from "next/link";
import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { NavBar } from "@/components/NavBar";
import { ToastHost } from "@/components/Toast";
import { BACKOFFICE_URL } from "@/lib/format";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin", "vietnamese"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Webshop",
  description: "Cửa hàng demo của hệ thống webshop microservices: SPU/SKU, đánh giá, Saga checkout.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="vi" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col">
        {/* NavBar đọc pathname (dữ liệu runtime) → phải nằm trong Suspense khi bật Cache Components */}
        <Suspense fallback={<div className="h-[57px] border-b border-border bg-surface" />}>
          <NavBar />
        </Suspense>
        <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6">{children}</main>
        <footer className="border-t border-border py-4 text-center text-xs text-muted">
          Dự án học tập — mọi request của cửa hàng đi qua Next.js BFF → API Gateway :8080 ·{" "}
          <Link className="underline" href="/demo">Kịch bản demo</Link> ·{" "}
          <a className="underline" href={BACKOFFICE_URL}>Backoffice ↗</a>
        </footer>
        <ToastHost />
      </body>
    </html>
  );
}
