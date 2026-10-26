/**
 * BFF (Backend-for-Frontend) proxy của STOREFRONT.
 *
 *   /bff/gateway/<path>  → API Gateway (chỉ /api/**)
 *
 * Khác backoffice: cửa hàng KHÔNG có đường gọi thẳng vào service (/admin/**, /actuator/**). Mọi request của khách đi
 * qua gateway — nơi kiểm tra JWT, giới hạn kích thước body, gắn correlation-id. Tách hai UI là tách luôn bề mặt tấn
 * công: lỗ hổng XSS ở cửa hàng không thể chạm tới endpoint vận hành.
 *
 * Trình duyệt chỉ nói chuyện với Next.js (cùng origin) → không cần CORS. URL backend đọc từ biến môi trường lúc chạy
 * (không bake vào build), nên cùng một image chạy được cả local lẫn Docker Compose.
 */

const GATEWAY_URL = process.env.GATEWAY_URL ?? "http://localhost:8080";

const FORWARDED_REQUEST_HEADERS = ["authorization", "content-type", "idempotency-key", "x-correlation-id"];
const FORWARDED_RESPONSE_HEADERS = ["content-type", "x-correlation-id", "location", "idempotent-replayed"];

type Context = { params: Promise<{ target: string; path: string[] }> };

async function proxy(request: Request, { params }: Context): Promise<Response> {
  const { target, path } = await params;
  const joined = path.join("/");
  if (target !== "gateway" || !/^api\//.test(joined)) {
    return Response.json(
      { code: "FORBIDDEN_PATH", message: "Storefront only proxies /api/** through the API Gateway" },
      { status: 403 },
    );
  }

  const url = new URL(`${GATEWAY_URL}/${joined}`);
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
      body: hasBody ? await request.arrayBuffer() : undefined,
      cache: "no-store",
      redirect: "manual",
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return Response.json(
      { code: "BFF_UPSTREAM_UNREACHABLE", message: `gateway unreachable: ${message}` },
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
