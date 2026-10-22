/**
 * BFF (Backend-for-Frontend) proxy.
 *
 *   /bff/gateway/<path>        → API Gateway (luồng thật của client: /api/**)
 *   /bff/<service>/<path>      → gọi THẲNG một service, CHỈ cho /admin/** và /actuator/**
 *                                (gateway cố ý không route các đường dẫn vận hành này)
 *
 * Trình duyệt chỉ nói chuyện với Next.js (cùng origin) → không cần CORS. URL backend đọc từ biến môi trường
 * lúc chạy (không bake vào build), nên cùng một image chạy được cả local lẫn Docker Compose.
 */

const TARGETS: Record<string, string | undefined> = {
  gateway: process.env.GATEWAY_URL ?? "http://localhost:8080",
  auth: process.env.AUTH_SERVICE_URL ?? "http://localhost:8081",
  product: process.env.PRODUCT_SERVICE_URL ?? "http://localhost:8082",
  cart: process.env.CART_SERVICE_URL ?? "http://localhost:8083",
  inventory: process.env.INVENTORY_SERVICE_URL ?? "http://localhost:8084",
  payment: process.env.PAYMENT_SERVICE_URL ?? "http://localhost:8085",
  order: process.env.ORDER_SERVICE_URL ?? "http://localhost:8086",
};

const FORWARDED_REQUEST_HEADERS = ["authorization", "content-type", "idempotency-key", "x-correlation-id"];
const FORWARDED_RESPONSE_HEADERS = ["content-type", "x-correlation-id", "location", "idempotent-replayed"];

type Context = { params: Promise<{ target: string; path: string[] }> };

async function proxy(request: Request, { params }: Context): Promise<Response> {
  const { target, path } = await params;
  const base = TARGETS[target];
  if (!base) {
    return Response.json({ code: "UNKNOWN_TARGET", message: `Unknown target ${target}` }, { status: 404 });
  }
  const joined = path.join("/");
  if (target !== "gateway" && !/^(admin|actuator)(\/|$)/.test(joined)) {
    return Response.json(
      { code: "FORBIDDEN_PATH", message: "Direct service access is limited to /admin/** and /actuator/**" },
      { status: 403 },
    );
  }

  const url = new URL(`${base}/${joined}`);
  url.search = new URL(request.url).search;

  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }

  const hasBody = !["GET", "HEAD"].includes(request.method);
  let upstream: Response;
  try {
    upstream = await fetch(url, {
      method: request.method,
      headers,
      body: hasBody ? await request.text() : undefined,
      cache: "no-store",
      redirect: "manual",
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return Response.json(
      { code: "BFF_UPSTREAM_UNREACHABLE", message: `${target} unreachable: ${message}` },
      { status: 502 },
    );
  }

  const responseHeaders = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) responseHeaders.set(name, value);
  }
  return new Response(await upstream.arrayBuffer(), { status: upstream.status, headers: responseHeaders });
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const DELETE = proxy;
