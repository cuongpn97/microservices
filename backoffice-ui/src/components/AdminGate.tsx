"use client";

import { useState } from "react";
import { gateway } from "@/lib/api";
import { saveSession, useSession } from "@/lib/session";
import type { ApiError, TokenResponse } from "@/lib/types";
import { ErrorBox } from "./ErrorBox";

/**
 * Mọi trang backoffice nằm sau cổng đăng nhập ADMIN. Đây chỉ là trải nghiệm người dùng — quyền thật do từng service
 * kiểm tra trên JWT (tắt đoạn này đi thì API vẫn trả 401/403).
 */
export function AdminGate({ children }: { children: React.ReactNode }) {
  const admin = useSession("admin");
  if (!admin) return <AdminLogin />;
  return <>{children}</>;
}

function AdminLogin() {
  const [email, setEmail] = useState("admin@webshop.local");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<ApiError | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const r = await gateway<TokenResponse>("/api/auth/login", { method: "POST", body: { email, password } });
    if (!r.ok || !r.data) return setError(r.error);
    if (!r.data.roles.includes("ADMIN")) {
      return setError({ status: 403, code: "NOT_ADMIN", message: "Tài khoản này không có role ADMIN" });
    }
    saveSession("admin", {
      token: r.data.accessToken,
      userId: r.data.userId,
      email,
      roles: r.data.roles,
      expiresAt: r.data.expiresAt,
    });
  }

  return (
    <form onSubmit={submit} className="card mx-auto mt-10 max-w-md space-y-4">
      <h1 className="h1">Đăng nhập backoffice</h1>
      <p className="muted">
        Dành cho nhân viên. Dùng tài khoản ADMIN tạo sẵn từ <code className="mono">ADMIN_EMAIL</code> /{" "}
        <code className="mono">ADMIN_PASSWORD</code> trong file <code className="mono">.env</code>.
      </p>
      <div>
        <label className="label" htmlFor="a-email">Email</label>
        <input id="a-email" className="input" value={email} onChange={(e) => setEmail(e.target.value)} />
      </div>
      <div>
        <label className="label" htmlFor="a-pass">Mật khẩu</label>
        <input id="a-pass" type="password" className="input" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="giá trị ADMIN_PASSWORD" />
      </div>
      <ErrorBox error={error} />
      <button className="btn btn-primary w-full">Đăng nhập</button>
    </form>
  );
}
