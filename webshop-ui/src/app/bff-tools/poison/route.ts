/**
 * Công cụ demo: publish một message KHÔNG phải JSON vào routing key của Payment để quan sát
 * retry/DLQ. Credential RabbitMQ chỉ nằm ở server (biến môi trường), không bao giờ gửi xuống trình duyệt.
 * Chỉ ADMIN dùng được: BFF xác minh token bằng cách gọi một endpoint /admin/** của payment-service.
 */
const PAYMENT_URL = process.env.PAYMENT_SERVICE_URL ?? "http://localhost:8085";
const RABBIT_MGMT_URL = process.env.RABBITMQ_MANAGEMENT_URL ?? "http://localhost:15672";

export async function POST(request: Request): Promise<Response> {
  const authorization = request.headers.get("authorization");
  if (!authorization) return Response.json({ code: "UNAUTHORIZED", message: "Admin token required" }, { status: 401 });

  const check = await fetch(`${PAYMENT_URL}/admin/dlq`, { headers: { authorization }, cache: "no-store" }).catch(() => null);
  if (!check || !check.ok) {
    return Response.json({ code: "FORBIDDEN", message: "Token is not an ADMIN token" }, { status: 403 });
  }

  const user = process.env.RABBITMQ_USERNAME;
  const password = process.env.RABBITMQ_PASSWORD;
  if (!user || !password) {
    return Response.json(
      { code: "NOT_CONFIGURED", message: "Set RABBITMQ_USERNAME / RABBITMQ_PASSWORD for the UI server" },
      { status: 500 },
    );
  }
  const messageId = `poison-${crypto.randomUUID()}`;
  const response = await fetch(`${RABBIT_MGMT_URL}/api/exchanges/%2F/webshop.events/publish`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Basic ${Buffer.from(`${user}:${password}`).toString("base64")}`,
    },
    body: JSON.stringify({
      properties: { delivery_mode: 2, message_id: messageId },
      routing_key: "payment.command.reserve",
      payload: "this is not json",
      payload_encoding: "string",
    }),
  }).catch((e: unknown) => new Response(String(e), { status: 502 }));
  const body = await response.text();
  return Response.json({ messageId, rabbitStatus: response.status, rabbit: body }, { status: response.ok ? 200 : 502 });
}
