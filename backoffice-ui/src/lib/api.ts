"use client";

import type { ApiError } from "./types";

export type ApiResult<T> = {
  ok: boolean;
  status: number;
  data: T | null;
  error: ApiError | null;
  headers: Headers;
  durationMs: number;
};

type Options = {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  token?: string | null;
  /** Object → gửi JSON; FormData → multipart/form-data (upload ảnh), trình duyệt tự đặt boundary. */
  body?: unknown;
  headers?: Record<string, string>;
};

/** Gọi qua BFF. target = "gateway" cho /api/**, hoặc tên service cho /admin/** và /actuator/**. */
export async function call<T>(target: string, path: string, options: Options = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { ...(options.headers ?? {}) };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  const isForm = options.body instanceof FormData;
  if (options.body !== undefined && !isForm) headers["Content-Type"] = "application/json";

  const started = performance.now();
  let response: Response;
  try {
    response = await fetch(`/bff/${target}${path}`, {
      method: options.method ?? "GET",
      headers,
      body: options.body === undefined ? undefined : isForm ? (options.body as FormData) : JSON.stringify(options.body),
      cache: "no-store",
    });
  } catch (e) {
    return {
      ok: false,
      status: 0,
      data: null,
      error: { status: 0, code: "NETWORK_ERROR", message: e instanceof Error ? e.message : String(e) },
      headers: new Headers(),
      durationMs: performance.now() - started,
    };
  }
  const durationMs = performance.now() - started;
  const text = await response.text();
  let parsed: unknown = null;
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = text;
    }
  }
  if (response.ok) {
    return { ok: true, status: response.status, data: parsed as T, error: null, headers: response.headers, durationMs };
  }
  const error: ApiError =
    parsed && typeof parsed === "object"
      ? (parsed as ApiError)
      : {
          status: response.status,
          code: response.status === 401 ? "UNAUTHORIZED" : response.status === 403 ? "FORBIDDEN" : `HTTP_${response.status}`,
          message:
            response.status === 401
              ? "Chưa đăng nhập hoặc token hết hạn"
              : response.status === 403
                ? "Không đủ quyền"
                : typeof parsed === "string" && parsed
                  ? parsed
                  : response.statusText,
        };
  return { ok: false, status: response.status, data: null, error, headers: response.headers, durationMs };
}

export const gateway = <T,>(path: string, options?: Options) => call<T>("gateway", path, options);

export function newIdempotencyKey(prefix = "checkout"): string {
  return `${prefix}-${crypto.randomUUID()}`;
}
