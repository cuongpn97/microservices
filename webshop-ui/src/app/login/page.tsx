"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { gateway } from "@/lib/api";
import { saveSession } from "@/lib/session";
import type { TokenResponse } from "@/lib/types";
import { toast } from "@/components/Toast";
import { ErrorBox } from "@/components/ErrorBox";
import type { ApiError } from "@/lib/types";

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<"login" | "register">("register");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("demo-password-123");
  const [fullName, setFullName] = useState("Demo User");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    if (mode === "register") {
      const r = await gateway("/api/auth/register", { method: "POST", body: { email, password, fullName } });
      if (!r.ok) {
        setError(r.error);
        setBusy(false);
        return;
      }
      toast("Đăng ký thành công — ví 10.000.000 ₫ đang được tạo bất đồng bộ", "ok");
    }
    const login = await gateway<TokenResponse>("/api/auth/login", { method: "POST", body: { email, password } });
    setBusy(false);
    if (!login.ok || !login.data) {
      setError(login.error);
      return;
    }
    saveSession("user", {
      token: login.data.accessToken,
      userId: login.data.userId,
      email: email.toLowerCase(),
      roles: login.data.roles,
      expiresAt: login.data.expiresAt,
    });
    router.push("/");
  }

  return (
    <div className="mx-auto grid max-w-4xl gap-8 md:grid-cols-[1fr_1.1fr]">
      <form onSubmit={submit} className="card space-y-4">
        <div className="flex gap-1 rounded-lg bg-surface-2 p-1 text-sm">
          {(["register", "login"] as const).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMode(m)}
              className={`flex-1 rounded-md py-1.5 font-medium ${mode === m ? "bg-surface shadow-sm" : "text-muted"}`}
            >
              {m === "register" ? "Đăng ký" : "Đăng nhập"}
            </button>
          ))}
        </div>
        <div>
          <label className="label" htmlFor="email">Email</label>
          <div className="flex gap-2">
            <input id="email" type="email" className="input" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" required />
            {mode === "register" && (
              <button type="button" className="btn btn-sm whitespace-nowrap" onClick={() => setEmail(`user${Date.now().toString(36)}@example.com`)}>
                Email ngẫu nhiên
              </button>
            )}
          </div>
        </div>
        <div>
          <label className="label" htmlFor="password">Mật khẩu (≥ 8 ký tự)</label>
          <input id="password" type="password" className="input" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </div>
        {mode === "register" && (
          <div>
            <label className="label" htmlFor="name">Họ tên</label>
            <input id="name" className="input" value={fullName} onChange={(e) => setFullName(e.target.value)} required />
          </div>
        )}
        <ErrorBox error={error} />
        <button className="btn btn-primary w-full" disabled={busy}>
          {busy ? "Đang xử lý…" : mode === "register" ? "Tạo tài khoản & đăng nhập" : "Đăng nhập"}
        </button>
      </form>

      <div className="space-y-4 text-sm">
        <h1 className="h1">Chuyện gì xảy ra khi bạn đăng ký?</h1>
        <ol className="list-decimal space-y-2 pl-5 text-muted">
          <li>
            <b className="text-text">Auth Service</b> lưu user (mật khẩu BCrypt) <i>và</i> event <code className="mono">UserRegistered</code> vào bảng outbox trong <b className="text-text">cùng một transaction</b>.
          </li>
          <li>Polling publisher gửi event lên RabbitMQ, chờ <i>publisher confirm</i> rồi mới đánh dấu đã gửi.</li>
          <li>
            <b className="text-text">Payment Service</b> nhận event, kiểm tra Inbox (chống trùng), tạo ví và ghi ledger <code className="mono">INITIAL_CREDIT</code> 10.000.000 ₫.
          </li>
          <li>
            Trong khoảng vài trăm ms, user đã tồn tại nhưng ví <i>chưa</i> có — góc phải thanh điều hướng sẽ hiện “Ví đang khởi tạo…”. Đó là <b className="text-text">eventual consistency</b>.
          </li>
        </ol>
        <p className="muted">
          Đăng nhập trả về JWT (HS256) chứa <code className="mono">sub</code>, <code className="mono">roles</code>, <code className="mono">exp</code>. Gateway và từng service đều tự kiểm tra token.
        </p>
      </div>
    </div>
  );
}
