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

## Product (`product-service`) — catalog SPU/SKU

**SPU** (sản phẩm: tên, slug, mô tả Markdown, thông số, ảnh, options) chứa nhiều **SKU** (biến thể bán được: một tổ hợp
giá trị option, có mã, giá, giá gạch ngang, ảnh riêng). Giỏ hàng, tồn kho, đơn hàng đều dùng `skuId`.

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/products?page=0&size=20` | public | SPU đang bán (`minPriceVnd`/`maxPriceVnd` qua các SKU, ảnh đại diện, rating) |
| GET | `/api/products/{idOrSlug}` | public | Chi tiết SPU đang bán, chỉ kèm SKU bán được; bản nháp → `404 PRODUCT_NOT_FOUND` |
| GET | `/api/products/skus/{skuId}` | public | Tra cứu SKU (contract của Cart/Order); trả cả SKU ngừng bán với `active:false` |
| GET | `/api/products/skus/batch?ids=..&ids=..` | public | Tối đa 100 skuId; id không tồn tại bị bỏ qua |
| GET | `/api/products/manage?q=&active=&page=&size=` | ADMIN | Danh sách quản trị (kể cả bản nháp) |
| GET | `/api/products/manage/{id}` | ADMIN | Chi tiết đầy đủ (mọi SKU) |
| POST | `/api/products/manage` | ADMIN | Tạo SPU (mặc định `active:false`); slug bỏ trống → sinh từ tên (bỏ dấu) |
| PUT | `/api/products/manage/{id}` | ADMIN | Ghi đè nội dung; `slug`/`active` bỏ trống = giữ nguyên; đổi options làm SKU sai → `422` |
| POST | `/api/products/manage/{id}/skus` | ADMIN | Thêm SKU (thuộc tính phải khớp options) |
| PUT | `/api/products/manage/{id}/skus/{skuId}` | ADMIN | Sửa SKU (không có DELETE: ngừng bán bằng `active:false`) |
| POST | `/api/products/manage/{id}/images` | ADMIN | `multipart/form-data`: `file` (JPEG/PNG/WebP ≤ 5MB, kiểm tra nội dung), `altText` |
| PUT | `/api/products/manage/{id}/images/order` | ADMIN | `{"imageIds":[...]}` — hoán vị đủ mọi ảnh; ảnh đầu là ảnh đại diện |
| PUT/DELETE | `/api/products/manage/{id}/images/{imageId}` | ADMIN | Sửa `altText` / xóa ảnh (SKU dùng ảnh đó quay về ảnh SPU) |

Mọi API ghi trả về chi tiết SPU mới nhất. Lỗi: `422 INVALID_CATALOG_CHANGE` (thuộc tính SKU sai, trùng tổ hợp, quá 10
ảnh, giá gạch ≤ giá bán...), `409 SKU_CODE_TAKEN` / `SLUG_TAKEN` / `DUPLICATE_VARIANT`, `422 UNSUPPORTED_IMAGE`,
`413 PAYLOAD_TOO_LARGE`, `503 DEPENDENCY_UNAVAILABLE` (object storage).

```bash
curl -s localhost:8080/api/products/ao-thun-cotton-basic
```

```json
{"id":"a0000000-0000-4000-8000-000000000006","slug":"ao-thun-cotton-basic","name":"Áo thun Cotton Basic",
 "brand":"Webshop Wear","shortDescription":"Áo thun 100% cotton, form regular","description":"Áo thun **100% cotton** ...",
 "specifications":[{"name":"Chất liệu","value":"100% cotton 220gsm"}],
 "options":[{"name":"Màu","values":["Đen","Trắng"]},{"name":"Size","values":["M","L","XL"]}],
 "skus":[{"id":"66666666-6666-6666-6666-000000000001","skuCode":"TEE-BLK-M","attributes":{"Màu":"Đen","Size":"M"},
          "variantName":"Đen / M","priceVnd":199000,"compareAtPriceVnd":null,"imageId":null,"imageUrl":null,
          "active":true,"sellable":true}],
 "images":[],"ratingAvg":4.5,"ratingCount":2,"active":true,"createdAt":"...","updatedAt":"...","version":3}
```

Dữ liệu seed (SKU id cố định, tồn kho seed bởi Inventory Service):

| SPU (slug) | SKU id | Mã | Biến thể | Giá (VND) | Tồn kho demo |
|---|---|---|---|---|---|
| `mechanical-keyboard` | `11111111-1111-1111-1111-111111111111` | KB87-RED | Red | 1.500.000 | 50 |
| | `11111111-1111-1111-1111-000000000002` | KB87-BLU | Blue | 1.550.000 | 30 |
| | `11111111-1111-1111-1111-000000000003` | KB87-BRN | Brown | 1.550.000 | 0 |
| `wireless-mouse` | `22222222-2222-2222-2222-222222222222` | MS24-BLK | Đen | 450.000 | 100 |
| | `22222222-2222-2222-2222-000000000002` | MS24-WHT | Trắng | 450.000 | 40 |
| `27-inch-monitor` | `33333333-3333-3333-3333-333333333333` | MON27-2K | — | 6.900.000 | 2 |
| `premium-laptop` | `44444444-4444-4444-4444-444444444444` | LAP-PRO | — | 25.000.000 | 10 |
| `discontinued-item` (inactive) | `55555555-5555-5555-5555-555555555555` | OLD-ITEM | — | 100.000 | — |
| `ao-thun-cotton-basic` | `66666666-6666-6666-6666-00000000000{1..6}` | TEE-{BLK,WHT}-{M,L,XL} | Màu × Size | 199.000–219.000 | 0–25 |
| `tai-nghe-bluetooth-anc` | `77777777-7777-7777-7777-00000000000{1,2}` | ANC-BLK, ANC-SLV | Đen, Bạc | 1.290.000 (gạch 1.590.000) | 15, 8 |

---

## Inventory (`inventory-service`) — theo SKU

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/inventory/{skuId}` | public | `stockOnHand`, `reservedQuantity`, `available` |
| GET | `/api/inventory/batch?ids=..&ids=..` | public | Tồn kho nhiều SKU một lần (1–100 id); id chưa có tồn kho bị bỏ qua |
| PUT | `/api/inventory/{skuId}` | ADMIN | Đặt `stockOnHand` (≥ `reservedQuantity`), tạo nếu chưa có |
| GET | `localhost:8084/admin/reservations/{orderId}` | ADMIN | Trạng thái reservation |

```bash
ADMIN=$(curl -s localhost:8080/api/auth/login -H 'content-type: application/json' \
  -d '{"email":"admin@webshop.local","password":"local-only-admin-pw"}' | jq -r .accessToken)
curl -s -X PUT localhost:8080/api/inventory/22222222-2222-2222-2222-222222222222 \
  -H "Authorization: Bearer $ADMIN" -H 'content-type: application/json' -d '{"stockOnHand":200}'
```

```json
{"skuId":"22222222-2222-2222-2222-222222222222","stockOnHand":200,"reservedQuantity":0,"available":200,"updatedAt":"..."}
```

Reserve/commit/release KHÔNG có HTTP public: chúng là command của Saga qua RabbitMQ (`ReserveInventory` v2: dòng hàng
theo `skuId`; v1 theo `productId` vẫn được nhận và upcast).

---

## Cart (`cart-service`) — cần token USER

| Method | Path | Body | Mô tả |
|---|---|---|---|
| GET | `/api/cart?pricing=true` | — | Giỏ + giá hiện tại (tham khảo). `pricing=false`: chỉ skuId/quantity |
| POST | `/api/cart/items` | `{"skuId","quantity"}` | Cộng thêm số lượng (1–99), SKU phải bán được |
| PUT | `/api/cart/items/{skuId}` | `{"quantity"}` | Đặt số lượng |
| DELETE | `/api/cart/items/{skuId}` | — | Xóa một dòng |
| DELETE | `/api/cart` | — | Xóa giỏ |

```bash
curl -s localhost:8080/api/cart/items -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"skuId":"66666666-6666-6666-6666-000000000001","quantity":2}'
curl -s localhost:8080/api/cart -H "Authorization: Bearer $TOKEN"
```

```json
{"userId":"945abf21-...","revision":1,"items":[
  {"skuId":"66666666-...","quantity":2,"productId":"a0000000-...","productSlug":"ao-thun-cotton-basic",
   "productName":"Áo thun Cotton Basic","skuCode":"TEE-BLK-M","variantName":"Đen / M","imageUrl":null,
   "unitPriceVnd":199000,"lineTotalVnd":398000,"active":true}],
 "estimatedTotalVnd":398000,"pricingAvailable":true,"updatedAt":"..."}
```

Khi Product Service lỗi / circuit OPEN: `GET` vẫn `200` với `pricingAvailable:false` (không có giá); `POST/PUT` trả `503 DEPENDENCY_UNAVAILABLE`.
Lỗi khác: `400` (quantity ≤ 0 hoặc > 99), `422 SKU_NOT_FOUND`, `422 PRODUCT_UNAVAILABLE`.

---

## Review (`review-service`)

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/reviews/products/{productId}?rating=&page=&size=` | public | Đánh giá PUBLISHED, mới nhất trước |
| GET | `/api/reviews/products/{productId}/summary` | public | `ratingCount`, `ratingAvg`, `histogram` (5→1 sao) |
| GET | `/api/reviews/products/{productId}/mine` | USER | `{"canReview": đã có đơn CONFIRMED chứa SPU, "review": bài của mình hoặc null}` |
| PUT | `/api/reviews/products/{productId}/mine` | USER | `{"rating":1..5,"title?","content"(10–4000)}` — tạo/sửa (idempotent); chưa mua → `403 NOT_PURCHASED` |
| GET | `/api/reviews/moderation?status=&productId=` | ADMIN | Mọi đánh giá, kể cả đã ẩn |
| PUT | `/api/reviews/moderation/{reviewId}/status` | ADMIN | `{"status":"HIDDEN"|"PUBLISHED"}` → tính lại điểm, phát `ProductRatingChanged` |

`productId` ở đây là id **SPU**. Lượt mua được ghi nhận từ event `OrderConfirmed` v2 (trễ vài trăm ms sau khi đơn CONFIRMED).

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
 "items":[{"skuId":"11111111-...","productId":"...","productName":"Mechanical Keyboard","skuCode":"KB87-RED",
           "variantName":"Red","unitPriceVnd":1500000,"quantity":2,"lineTotalVnd":3000000},
          {"skuId":"22222222-...","productId":"...","productName":"Wireless Mouse","skuCode":"MS24-BLK",
           "variantName":"Đen","unitPriceVnd":450000,"quantity":1,"lineTotalVnd":450000}],
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

`failureReason` có thể là: `INVENTORY_REJECTED:INSUFFICIENT_STOCK:<skuId>`, `INVENTORY_REJECTED:UNKNOWN_SKU:<skuId>`,
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
| `q` | — | Từ khóa (≤ 200 ký tự), không phân biệt dấu (`ban phim` khớp "Bàn phím"), gõ sai nhẹ, tiền tố, giá trị biến thể (`ao den`), mã SKU (`kb87-red`) |
| `minPriceVnd`, `maxPriceVnd` | — | Khoảng giá (VND); SPU khớp khi khoảng [giá thấp nhất, cao nhất] của nó giao với khoảng lọc |
| `sort` | `RELEVANCE` | `RELEVANCE` · `PRICE_ASC` (theo giá thấp nhất) · `PRICE_DESC` (theo giá cao nhất) · `RATING` · `NAME` |
| `page`, `size` | `0`, `20` | `size` ≤ 50; `(page + 1) * size` ≤ 10 000 (lỗi `PAGE_TOO_DEEP`) |

```bash
curl -s 'localhost:8080/api/search/products?q=ban%20phim&sort=PRICE_ASC'
```

```json
{"items":[{"productId":"...","slug":"mechanical-keyboard","name":"Mechanical Keyboard","brand":"Webshop Gear",
  "shortDescription":"Bàn phím cơ 87 phím, hot-swap, đèn nền trắng","minPriceVnd":1500000,"maxPriceVnd":1550000,
  "imageUrl":null,"ratingAvg":null,"ratingCount":0,"score":3.2}],"page":0,"size":20,"totalElements":1,"tookMs":4}
```

Dữ liệu là read model dựng từ CDC: trễ vài giây so với Product Service. **Không trả tồn kho** (thay đổi quá thường
xuyên để index — xem design-decisions mục 12): lấy số thật theo skuId bằng `GET /api/inventory/batch?ids=..` ở trang
chi tiết sản phẩm. Document dựng từ bảng `catalog_document` (design-decisions mục 13). Elasticsearch không kết nối được ⇒ `503 DEPENDENCY_UNAVAILABLE`.

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
