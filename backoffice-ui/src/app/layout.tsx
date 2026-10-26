import { Suspense } from "react";
import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { AdminGate } from "@/components/AdminGate";
import { NavBar } from "@/components/NavBar";
import { ToastHost } from "@/components/Toast";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin", "vietnamese"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Webshop Backoffice",
  description: "Quản trị catalog SPU/SKU, ảnh, tồn kho, đánh giá và vận hành hệ thống webshop microservices.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="vi" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col">
        {/* NavBar đọc pathname (dữ liệu runtime) → phải nằm trong Suspense khi bật Cache Components */}
        <Suspense fallback={<div className="h-[57px] border-b border-border bg-surface" />}>
          <NavBar />
        </Suspense>
        <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6">
          <AdminGate>{children}</AdminGate>
        </main>
        <footer className="border-t border-border py-4 text-center text-xs text-muted">
          Backoffice — nghiệp vụ đi qua API Gateway; trang Vận hành gọi thẳng /admin/** của từng service.
        </footer>
        <ToastHost />
      </body>
    </html>
  );
}
