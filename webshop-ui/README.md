# webshop-ui — giao diện demo (Next.js 16)

Giao diện thay cho `scripts/demo.sh`: đăng ký/đăng nhập, cửa hàng, giỏ hàng, checkout, **theo dõi Saga trực tiếp**,
ví + sổ cái, trang **Kịch bản demo** (chạy tự động các tình huống thành công/thất bại) và trang **Quản trị**
(sức khỏe hệ thống, outbox backlog, circuit breaker, DLQ peek/redrive, tra cứu Saga, gửi poison message).

## Chạy

Cách 1 — cùng Docker Compose (khuyến nghị):

```bash
docker compose up -d --build          # ở thư mục gốc repo; UI ở http://localhost:3000
```

Cách 2 — chạy dev ngoài Docker (backend vẫn chạy bằng compose):

```bash
cd webshop-ui
cp .env.example .env.local            # điền RABBITMQ_PASSWORD từ ../.env nếu muốn dùng nút "Gửi message hỏng"
npm install
npm run dev                           # http://localhost:3000
```

## Kiến trúc: BFF proxy

```
Trình duyệt ──(cùng origin)──► Next.js
                                 ├─ /bff/gateway/<path>  ──► API Gateway :8080   (luồng thật: /api/**)
                                 ├─ /bff/<service>/<path> ──► service trực tiếp   (CHỈ /admin/** và /actuator/**)
                                 └─ /bff-tools/poison     ──► RabbitMQ Management API (sau khi xác minh token ADMIN)
```

- `src/app/bff/[target]/[...path]/route.ts`: Route Handler làm proxy. Trình duyệt không gọi thẳng backend → không cần CORS,
  URL backend đọc từ env lúc chạy (cùng một image dùng được cho local lẫn Docker).
- Đường dẫn tới service trực tiếp bị giới hạn theo allow-list (`/admin/**`, `/actuator/**`) để BFF không trở thành
  cửa hậu đi vòng qua gateway.
- Credential RabbitMQ chỉ nằm ở server Next.js, không bao giờ xuống trình duyệt.

## Lưu ý bảo mật (vì đây là bản demo)

- Access token được lưu trong `localStorage` cho đơn giản. Production nên để BFF giữ token trong cookie
  `HttpOnly; Secure; SameSite` để JavaScript (và XSS) không đọc được.
- Phiên quản trị (token ADMIN) tách riêng với phiên mua hàng.

## Cấu trúc

| Đường dẫn | Nội dung |
|---|---|
| `src/app/page.tsx` | Cửa hàng: sản phẩm (Product Service) + tồn kho (Inventory Service) |
| `src/app/cart/page.tsx` | Giỏ hàng, Idempotency-Key, checkout |
| `src/app/orders/[id]/OrderDetail.tsx` | Theo dõi Saga (poll 0,7s), snapshot giá, nhật ký `saga_transition` (khi có token ADMIN) |
| `src/app/scenarios/page.tsx` | 6 kịch bản tự động + hướng dẫn kịch bản sự cố hạ tầng |
| `src/app/admin/page.tsx` | Vận hành: health, outbox backlog, breaker, tồn kho, Saga, DLQ |
| `src/components/SagaTracker.tsx` | Sơ đồ bước Saga (đường thành công / bồi hoàn) |
| `src/lib/api.ts`, `session.ts`, `hooks.ts` | Gọi BFF, phiên đăng nhập, tải ví/giỏ |

Phiên bản: Next.js 16.4.2 (App Router, Cache Components bật), React 19, Tailwind CSS 4.
