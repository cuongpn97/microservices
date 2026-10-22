# Tài liệu API

Mọi request của client đi qua **gateway `http://localhost:8080`**. Endpoint `/admin/**` và `/actuator/**` (trừ health)
chỉ gọi trực tiếp port của service và cần token có role `ADMIN`.

**Swagger / OpenAPI** (springdoc 2.8.17):

| URL | Ghi chú |
|---|---|
| http://localhost:8080/swagger-ui.html | Tổng hợp 6 service qua gateway (spec được proxy tại `/docs/<service>/v3/api-docs`) |
| http://localhost:808x/swagger-ui.html, `/v3/api-docs` | Của từng service (8081 auth … 8086 order) — thử được `/admin/**` |

Spec khai báo security scheme `bearerAuth` (HTTP bearer, JWT) và server tương đối `/`.

Header chung:

| Header | Ý nghĩa |
|---|---|
| `Authorization: Bearer <accessToken>` | Bắt buộc với mọi endpoint không public |
| `X-Correlation-Id` | Tùy chọn. Gateway sinh nếu thiếu và trả lại trong response; xuất hiện trong log mọi service |
| `Idempotency-Key` | **Bắt buộc** cho `POST /api/orders/checkout` (8–100 ký tự `A-Z a-z 0-9 _ : . -`) |

Định dạng lỗi thống nhất:

```json
{
  "timestamp": "2026-10-21T14:30:46.110Z",
  "status": 422,
  "code": "PRODUCT_UNAVAILABLE",
  "message": "Product is no longer available: 5555...",
  "correlationId": "a4e298f9-f82b-46d3-8342-17737e7039fd",
  "violations": []
}
```

Tiền tệ: số nguyên VND (`long`), không có phần thập phân.

---

## Auth (`auth-service`)

### `POST /api/auth/register` — public

```bash
curl -s localhost:8080/api/auth/register -H 'content-type: application/json' \
  -d '{"email":"alice@example.com","password":"alice-password-1","fullName":"Alice"}'
```

`201 Created`

```json
{"id":"945abf21-af6e-4051-826f-02ecb11c1aec","email":"alice@example.com","fullName":"Alice","roles":["USER"],"createdAt":"2026-10-21T14:30:35.9Z"}
```

Lỗi: `400 VALIDATION_FAILED` (email sai, mật khẩu < 8 ký tự), `409 EMAIL_ALREADY_REGISTERED`.
Role luôn là `USER`; field `roles` trong body bị bỏ qua.

### `POST /api/auth/login` — public

```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'content-type: application/json' \
  -d '{"email":"alice@example.com","password":"alice-password-1"}' | jq -r .accessToken)
```

`200 OK`

```json
{"accessToken":"eyJhbGciOiJIUzI1NiJ9...","tokenType":"Bearer","expiresIn":1800,"expiresAt":"2026-10-21T15:00:36Z","userId":"945abf21-...","roles":["USER"]}
```

Claims: `sub` (userId), `iss` (`webshop-auth`), `iat`, `exp`, `roles`. Sai email/mật khẩu: `401 INVALID_CREDENTIALS` (thông báo chung).

### `GET /api/auth/me` — cần token

---

## Product (`product-service`)

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/products?page=0&size=20` | public | Sản phẩm đang bán |
| GET | `/api/products/{id}` | public | Kể cả sản phẩm inactive (có cờ `active`) |
| GET | `/api/products/batch?ids=a,b,c` | public | Tối đa 100 id |
| POST | `/api/products` | ADMIN | Tạo |
| PUT | `/api/products/{id}` | ADMIN | Sửa (đổi giá chỉ ảnh hưởng đơn tương lai) |

```bash
curl -s localhost:8080/api/products/11111111-1111-1111-1111-111111111111
```

```json
{"id":"11111111-1111-1111-1111-111111111111","name":"Mechanical Keyboard","description":"Bàn phím cơ 87 phím","priceVnd":1500000,"active":true,"updatedAt":"..."}
```

Dữ liệu seed:

| productId | Tên | Giá (VND) | Tồn kho demo |
|---|---|---|---|
| `11111111-1111-1111-1111-111111111111` | Mechanical Keyboard | 1.500.000 | 50 |
| `22222222-2222-2222-2222-222222222222` | Wireless Mouse | 450.000 | 100 |
| `33333333-3333-3333-3333-333333333333` | 27-inch Monitor | 6.900.000 | 2 |
| `44444444-4444-4444-4444-444444444444` | Premium Laptop | 25.000.000 | 10 |
| `55555555-5555-5555-5555-555555555555` | Discontinued Item (inactive) | 100.000 | — |

---

## Inventory (`inventory-service`)

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/inventory/{productId}` | public | `stockOnHand`, `reservedQuantity`, `available` |
| GET | `/api/inventory/batch?ids=..&ids=..` | public | Tồn kho nhiều sản phẩm một lần (1–100 id); id chưa có tồn kho bị bỏ qua |
| PUT | `/api/inventory/{productId}` | ADMIN | Đặt `stockOnHand` (≥ `reservedQuantity`), tạo nếu chưa có |
| GET | `localhost:8084/admin/reservations/{orderId}` | ADMIN | Trạng thái reservation |

```bash
ADMIN=$(curl -s localhost:8080/api/auth/login -H 'content-type: application/json' \
  -d '{"email":"admin@webshop.local","password":"local-only-admin-pw"}' | jq -r .accessToken)
curl -s -X PUT localhost:8080/api/inventory/22222222-2222-2222-2222-222222222222 \
  -H "Authorization: Bearer $ADMIN" -H 'content-type: application/json' -d '{"stockOnHand":200}'
```

```json
{"productId":"22222222-2222-2222-2222-222222222222","stockOnHand":200,"reservedQuantity":0,"available":200,"updatedAt":"..."}
```

Reserve/commit/release KHÔNG có HTTP public: chúng là command của Saga qua RabbitMQ.

---

## Cart (`cart-service`) — cần token USER

| Method | Path | Body | Mô tả |
|---|---|---|---|
| GET | `/api/cart?pricing=true` | — | Giỏ + giá hiện tại (tham khảo). `pricing=false`: chỉ productId/quantity |
| POST | `/api/cart/items` | `{"productId","quantity"}` | Cộng thêm số lượng (1–99), sản phẩm phải active |
| PUT | `/api/cart/items/{productId}` | `{"quantity"}` | Đặt số lượng |
| DELETE | `/api/cart/items/{productId}` | — | Xóa một sản phẩm |
| DELETE | `/api/cart` | — | Xóa giỏ |

```bash
curl -s localhost:8080/api/cart/items -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"productId":"11111111-1111-1111-1111-111111111111","quantity":2}'
curl -s localhost:8080/api/cart -H "Authorization: Bearer $TOKEN"
```

```json
{"userId":"945abf21-...","revision":2,"items":[
  {"productId":"11111111-...","quantity":2,"name":"Mechanical Keyboard","unitPriceVnd":1500000,"lineTotalVnd":3000000,"active":true},
  {"productId":"22222222-...","quantity":1,"name":"Wireless Mouse","unitPriceVnd":450000,"lineTotalVnd":450000,"active":true}],
 "estimatedTotalVnd":3450000,"pricingAvailable":true,"updatedAt":"..."}
```

Khi Product Service lỗi / circuit OPEN: `GET` vẫn `200` với `pricingAvailable:false` (không có giá); `POST/PUT` trả `503 DEPENDENCY_UNAVAILABLE`.
Lỗi khác: `400` (quantity ≤ 0 hoặc > 99), `422 PRODUCT_NOT_FOUND`, `422 PRODUCT_UNAVAILABLE`.

---

## Wallet (`payment-service`) — cần token

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/wallet/me` | `balanceVnd`, `reservedVnd`, `availableVnd`, `totalCapturedVnd` |
| GET | `/api/wallet/me/ledger?limit=50` | Sổ cái (mới nhất trước) |
| GET | `localhost:8085/admin/wallets/{userId}` | ADMIN |
| GET | `localhost:8085/admin/credit-reservations/{orderId}` | ADMIN |

```json
{"userId":"945abf21-...","balanceVnd":6550000,"reservedVnd":0,"availableVnd":6550000,"totalCapturedVnd":3450000,"updatedAt":"..."}
```

Ngay sau đăng ký có thể nhận `404 WALLET_NOT_INITIALIZED` trong thời gian ngắn (eventual consistency) — thử lại sau.

Ledger:

```json
[{"id":"...","type":"CAPTURE","amountVnd":3450000,"balanceAfterVnd":6550000,"reservedAfterVnd":0,"referenceId":"5ed07d10-...","createdAt":"..."},
 {"id":"...","type":"RESERVE","amountVnd":3450000,"balanceAfterVnd":10000000,"reservedAfterVnd":3450000,"referenceId":"5ed07d10-...","createdAt":"..."},
 {"id":"...","type":"INITIAL_CREDIT","amountVnd":10000000,"balanceAfterVnd":10000000,"reservedAfterVnd":0,"referenceId":"945abf21-...","createdAt":"..."}]
```

---

## Orders (`order-service`) — cần token USER

### `POST /api/orders/checkout`

```bash
curl -s -i -X POST localhost:8080/api/orders/checkout \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: checkout-$(uuidgen)"
```

`202 Accepted` (lần đầu) hoặc `200 OK` + `Idempotent-Replayed: true` (gửi lại cùng key). Header `Location: /api/orders/{id}`.

```json
{"orderId":"5ed07d10-9767-469a-bc20-3252806d44ea","status":"PENDING","sagaState":"PENDING","totalVnd":3450000,
 "items":[{"productId":"11111111-...","productName":"Mechanical Keyboard","unitPriceVnd":1500000,"quantity":2,"lineTotalVnd":3000000},
          {"productId":"22222222-...","productName":"Wireless Mouse","unitPriceVnd":450000,"quantity":1,"lineTotalVnd":450000}],
 "failureReason":null,"createdAt":"...","updatedAt":"..."}
```

| Mã | Khi nào |
|---|---|
| `400 INVALID_IDEMPOTENCY_KEY` | Thiếu/sai định dạng header |
| `401` | Thiếu/sai/hết hạn token |
| `409 CHECKOUT_ALREADY_IN_PROGRESS` | Đã có đơn đang hoạt động cho cùng phiên bản giỏ (khác key) |
| `422 CART_EMPTY`, `PRODUCT_UNAVAILABLE`, `INVALID_QUANTITY` | Dữ liệu không hợp lệ |
| `503 DEPENDENCY_UNAVAILABLE` | Cart/Product Service lỗi hoặc circuit OPEN — chưa ghi gì, an toàn để retry cùng key |

### `GET /api/orders/{orderId}` — poll trạng thái

| `status` | `sagaState` | Ý nghĩa |
|---|---|---|
| PENDING | PENDING | Chờ Inventory giữ hàng |
| PENDING | INVENTORY_RESERVED | Đã giữ hàng, chờ Payment giữ credit |
| PENDING | CONFIRMING | Đã giữ cả hai, đang commit kho + capture credit (chắc chắn sẽ CONFIRMED) |
| PENDING | COMPENSATING | Đã thất bại, đang trả lại tài nguyên |
| CONFIRMED | COMPLETED | Thành công |
| FAILED | FAILED | Thất bại, `failureReason` cho biết lý do; không bị trừ tiền |

`failureReason` có thể là: `INVENTORY_REJECTED:INSUFFICIENT_STOCK:<productId>`, `INVENTORY_REJECTED:UNKNOWN_PRODUCT:<id>`,
`CREDIT_REJECTED:INSUFFICIENT_FUNDS`, `CREDIT_REJECTED:WALLET_NOT_FOUND`, `INVENTORY_TIMEOUT`, `PAYMENT_TIMEOUT`.

Đơn của người khác trả `404`.

### `GET /api/orders` — 50 đơn gần nhất của user

### Admin (port 8086, role ADMIN)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/admin/sagas/{sagaId}` | State, bước từng participant, số lần thử, deadline, lịch sử chuyển trạng thái |
| GET | `/admin/sagas/attention` | Saga có `needsAttention = true` |
| POST | `/admin/sagas/{sagaId}/retry-now` | Đặt deadline = now để watcher xử lý ngay |

---

## Search (`search-service`, profile `search`) — public

### `GET /api/search/products`

| Tham số | Mặc định | Mô tả |
|---|---|---|
| `q` | — | Từ khóa (≤ 200 ký tự), không phân biệt dấu (`ban phim` khớp "Bàn phím"), chấp nhận gõ sai nhẹ và tiền tố |
| `minPriceVnd`, `maxPriceVnd` | — | Khoảng giá (VND, số nguyên) |
| `sort` | `RELEVANCE` | `RELEVANCE` · `PRICE_ASC` · `PRICE_DESC` · `NAME` |
| `page`, `size` | `0`, `20` | `size` ≤ 50; `(page + 1) * size` ≤ 10 000 (lỗi `PAGE_TOO_DEEP`) |

```bash
curl -s 'localhost:8080/api/search/products?q=ban%20phim&sort=PRICE_ASC'
```

```json
{"items":[{"productId":"11111111-1111-1111-1111-111111111111","name":"Mechanical Keyboard","description":"Bàn phím cơ 87 phím",
  "priceVnd":1500000,"score":3.2}],"page":0,"size":20,"totalElements":1,"tookMs":4}
```

Dữ liệu là read model dựng từ CDC: trễ vài giây so với Product Service. **Không trả tồn kho** (thay đổi quá thường
xuyên để index — xem design-decisions mục 12): lấy số thật bằng `GET /api/inventory/batch?ids=..` cho các sản phẩm
đang hiển thị. Elasticsearch không kết nối được ⇒ `503 DEPENDENCY_UNAVAILABLE`.

---

## DLQ admin (mọi service có consumer, role ADMIN)

| Method | Path |
|---|---|
| GET | `/admin/dlq` |
| GET | `/admin/dlq/{dlqName}/messages?max=10` |
| POST | `/admin/dlq/{dlqName}/redrive?max=10` |

Xem [failure-scenarios.md](failure-scenarios.md#5-dlq-kiểm-tra-redrive-replay).

---

## Actuator

| Path | Quyền |
|---|---|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | public |
| `/actuator/prometheus`, `/actuator/info` | public (chỉ nên mở trong mạng nội bộ) |
| `/actuator/metrics/**`, `/actuator/circuitbreakers` (cart, order) | ADMIN |

Ví dụ: `curl -s localhost:8086/actuator/prometheus | grep webshop_saga`.
