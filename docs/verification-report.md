# Báo cáo kiểm chứng

Các lệnh dưới đây đã **thực sự được chạy** trong phiên tạo dự án, ngày 2026-10-21, trên Windows 11 Pro,
JDK 25.0.4 (compile `--release 21`), Docker Engine 28.0.1 (Docker Desktop), Docker Compose v2.33.1.
Kết quả được trích nguyên văn (rút gọn) từ output.

## 1. Kiểm tra theo từng phase (trong lúc xây dựng)

| Phase / module | Lệnh | Kết quả lần đầu | Sửa | Kết quả cuối |
|---|---|---|---|---|
| common-web | `./mvnw -B -f libs/common-web/pom.xml install` | 4/5 pass — `readTimeoutIsTreatedAsTransientAndRetriedBoundedly` fail (HttpServer test đơn luồng nên lần retry thứ 2 không được tính) | Cho HttpServer test dùng thread pool | 5/5 pass |
| common-messaging | `./mvnw -B -f libs/common-messaging/pom.xml install` | Outbox 5/5 pass; `ReliableConsumerIT` 1/6 pass | Nguyên nhân: 2 lớp test tạo 2 Spring context (khác `@DynamicPropertySource`) ⇒ 2 listener cùng consume `test.q`. Gộp vào base class `AbstractMessagingIT` | 11/11 pass |
| auth-service | `./mvnw -B -f auth-service/pom.xml verify` | 5/6 — test đọc `iss` dạng URL | Đọc claim dạng String | 6/6 |
| product-service | `./mvnw -B -f product-service/pom.xml install` | Context không lên: `NoClassDefFoundError RabbitMQContainer` (SharedContainers khởi tạo eager) | Holder class lazy | 6/6 |
| inventory-service | `./mvnw -B -f inventory-service/pom.xml install` | 15/15 | — | 15/15 |
| payment-service | `./mvnw -B -f payment-service/pom.xml install` | 12/12 | — | 12/12 |
| cart-service | `./mvnw -B -f cart-service/pom.xml install` | 7/7 | — | 7/7 |
| order-service | `./mvnw -B -f order-service/pom.xml install` | 24/24 | — | 24/24 |
| api-gateway | `./mvnw -B -f api-gateway/pom.xml install` | 6/6 | — | 6/6 |

## 2. Build toàn bộ reactor

```
$ ./mvnw -B clean verify
[INFO] Reactor Summary for webshop-parent 1.0.0-SNAPSHOT:
[INFO] webshop-parent ..................................... SUCCESS
[INFO] common-web ......................................... SUCCESS   Tests run: 5,  Failures: 0, Errors: 0
[INFO] common-messaging ................................... SUCCESS   Tests run: 11, Failures: 0, Errors: 0
[INFO] api-gateway ........................................ SUCCESS   Tests run: 6,  Failures: 0, Errors: 0
[INFO] auth-service ....................................... SUCCESS   Tests run: 6,  Failures: 0, Errors: 0
[INFO] product-service .................................... SUCCESS   Tests run: 6,  Failures: 0, Errors: 0
[INFO] cart-service ....................................... SUCCESS   Tests run: 7,  Failures: 0, Errors: 0
[INFO] inventory-service .................................. SUCCESS   Tests run: 15, Failures: 0, Errors: 0
[INFO] payment-service .................................... SUCCESS   Tests run: 12, Failures: 0, Errors: 0
[INFO] order-service ...................................... SUCCESS   Tests run: 24, Failures: 0, Errors: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  03:29 min
```

Tổng: **92 test, 0 failure, 0 error, 0 skipped** (lần chạy đầu, trước khi sửa lỗi `OrderFailed` ở mục 5).
Lần chạy lại sau khi sửa: xem mục 6.

## 3. Mỗi service build độc lập

```
$ for m in api-gateway auth-service product-service cart-service inventory-service payment-service order-service; do
    ./mvnw -q -B -pl $m -am package -DskipTests && echo "$m: independent build OK"; done
api-gateway: independent build OK
auth-service: independent build OK
product-service: independent build OK
cart-service: independent build OK
inventory-service: independent build OK
payment-service: independent build OK
order-service: independent build OK
```

## 4. Môi trường Docker Compose

```
$ cp .env.example .env
$ docker compose config --quiet && echo CONFIG_OK
CONFIG_OK
$ docker compose build        → 7 image Built
$ docker compose up -d
$ docker compose ps
api-gateway         Up (healthy)
auth-service        Up (healthy)
cart-service        Up (healthy)
inventory-service   Up (healthy)
order-service       Up (healthy)
payment-service     Up (healthy)
postgres            Up (healthy)
product-service     Up (healthy)
rabbitmq            Up (healthy)
```

Cô lập database (user của service này không vào được database của service khác):

```
$ docker compose exec -T postgres psql -U cart_user -d payment_db -c 'select 1'
psql: error: ... FATAL:  permission denied for database "payment_db"
DETAIL:  User does not have CONNECT privilege.
```

Topology RabbitMQ (qua Management API): 5 queue chính durable với `x-dead-letter-exchange=webshop.dlx`, mỗi queue có
`.retry.1..3` và `.dlq`.

Truy cập thẳng service không qua gateway vẫn bị kiểm tra JWT:

```
$ curl -s -o /dev/null -w "%{http_code}" -X POST localhost:8086/api/orders/checkout
401
```

## 5. Demo end-to-end

### `bash scripts/demo.sh` — PASSED

```
==> 1. Đăng ký user mới ...                      OK: registered demo-...@example.com and logged in
==> 2. Chờ ví được khởi tạo                     {"balanceVnd":10000000,"reservedVnd":0,"availableVnd":10000000,...}  OK
==> 3. Danh sách sản phẩm (public)              OK
==> 4. Checkout khi chưa đăng nhập              OK: 401
==> 5. Thêm vào giỏ                             estimatedTotalVnd=3450000  OK (quantity 0 → 400)
==> 6. Checkout với Idempotency-Key             orderId=5ed07d10-... status=PENDING total=3450000
==> 7. Retry cùng Idempotency-Key               OK: same orderId returned
==> 8. Poll                                     {"status":"CONFIRMED","sagaState":"COMPLETED",...}  OK
==> 9. Ví sau capture                           {"balanceVnd":6550000,"reservedVnd":0,"totalCapturedVnd":3450000}  OK
==> 10. Giỏ được dọn sau OrderConfirmed         OK
==> 11. Laptop 25.000.000 > credit              {"status":"FAILED","failureReason":"CREDIT_REJECTED:INSUFFICIENT_FUNDS"}
                                                OK: inventory reservation released, wallet untouched
==> 12. Monitor hết hàng                        {"status":"FAILED","failureReason":"INVENTORY_REJECTED:INSUFFICIENT_STOCK:3333..."}  OK
DEMO PASSED
```

Lịch sử Saga của đơn thành công (`GET localhost:8086/admin/sagas/5ed07d10-...`):
`null→PENDING (CheckoutRequested)`, `PENDING→INVENTORY_RESERVED (InventoryReserved)`,
`INVENTORY_RESERVED→CONFIRMING (CreditReserved)`, `CONFIRMING→COMPLETED (InventoryCommitted)` — tổng ~3,6 giây.

Log JSON (ECS) của hai service khác nhau mang cùng `correlationId` và `sagaId`:

```
order-service   ... "message":"Saga 5ed07d10-... CONFIRMING -> COMPLETED on InventoryCommitted","correlationId":"a4e298f9-f82b-46d3-8342-17737e7039fd","sagaId":"5ed07d10-..."
payment-service ... "message":"Credit captured reservationId=5ed07d10-... amountVnd=3450000","correlationId":"a4e298f9-f82b-46d3-8342-17737e7039fd","sagaId":"5ed07d10-..."
```

### `bash scripts/demo-failures.sh` — FINISHED (lần chạy thứ hai)

```
==> A. Dừng payment-service rồi checkout
    sau 8s khi payment down: sagaState=INVENTORY_RESERVED
    OK: Saga đang chờ ở INVENTORY_RESERVED (ReserveCredit nằm trong queue durable)
    OK: payment-service lên lại → xử lý command tồn đọng → đơn CONFIRMED
    OK: chỉ trừ tiền một lần dù ReserveCredit có thể đã được gửi lại (balance=8,500,000)
==> B. Poison message → DLQ
    DLQ trước: {"payment.commands.q.dlq":1}   DLQ sau: {"payment.commands.q.dlq":2}
    [{"messageId":"poison-demo","originalRoutingKey":"payment.command.reserve","attempt":1,"failureReason":"UNPARSEABLE",...}]
==> C. Dừng product-service
    GET /api/cart → 200, pricingAvailable=false
    "product-service":{"failureRate":"50.0%",...,"failedCalls":8,"notPermittedCalls":5,"state":"OPEN"}
    OK: giỏ vẫn đọc được (không có giá), circuit OPEN → fail-fast 503
FAILURE DEMO FINISHED
```

Lần chạy **đầu tiên** của script này, bước C không kích hoạt circuit breaker vì giỏ hàng đã trống (Cart không cần gọi
Product Service) — lỗi của script, không phải của service. Đã sửa script (thêm hàng vào giỏ trước khi dừng Product
Service, và kiểm tra `state=OPEN`), chạy lại như trên.

### Lỗi thật phát hiện nhờ metric, đã sửa

Sau demo, `webshop_outbox_backlog{application="order-service"} = 2`. Truy vấn:

```
 event_type  | routing_key  | publish_attempts | err
 OrderFailed | order.failed |               11 | IllegalStateException: Message unroutable (returned): NO_ROUTE ...
```

Nguyên nhân: Order Service publish `OrderFailed` nhưng không có queue nào bind `order.failed`; publisher (đúng thiết kế)
coi `NO_ROUTE` là lỗi nên thử lại mãi. Sửa: bỏ event không có consumer (`CheckoutSagaOrchestrator`), cập nhật test và
tài liệu (design-decisions §4). Đã xóa 2 dòng kẹt trong database local, rebuild/redeploy order-service, chạy lại
**cả hai** script demo (PASSED/FINISHED), sau đó:

```
webshop_outbox_backlog{application="order-service"} 0.0
webshop_outbox_backlog{application="auth-service"} 0.0
webshop_outbox_backlog{application="inventory-service"} 0.0
webshop_outbox_backlog{application="payment-service"} 0.0
webshop_outbox_backlog{application="cart-service"} 0.0
```

## 6. Build toàn bộ reactor lần cuối (sau mọi thay đổi)

```
$ ./mvnw -B clean verify      (chạy song song với stack docker compose đang hoạt động)
[INFO] webshop-parent ..................................... SUCCESS
[INFO] common-web ......................................... SUCCESS   Tests run: 5
[INFO] common-messaging ................................... SUCCESS   Tests run: 11
[INFO] api-gateway ........................................ SUCCESS   Tests run: 6
[INFO] auth-service ....................................... SUCCESS   Tests run: 6
[INFO] product-service .................................... SUCCESS   Tests run: 6
[INFO] cart-service ....................................... SUCCESS   Tests run: 7
[INFO] inventory-service .................................. SUCCESS   Tests run: 15
[INFO] payment-service .................................... SUCCESS   Tests run: 12
[INFO] order-service ...................................... SUCCESS   Tests run: 24
[INFO] BUILD SUCCESS
[INFO] Total time:  03:35 min
```

Tổng: **92 test, 0 failure, 0 error, 0 skipped.** Các dòng log mức ERROR trong output (ví dụ `Retries exhausted ... -> DLQ`,
`duplicate key value violates unique constraint "ux_orders_user_idempotency"`) là hành vi được test cố ý kích hoạt.

## 7. Ánh xạ yêu cầu kiểm thử → test

| Yêu cầu | Test |
|---|---|
| Unit test luật nghiệp vụ / chuyển trạng thái | `CheckoutSagaTest` (10), `InventoryItemTest` (5) |
| Repository transaction + Flyway migration | Mọi `*IT` chạy Flyway trên PostgreSQL thật (`ddl-auto=validate`); `databaseConstraintIsTheLastLineOfDefence...`, `ledgerIsAppendOnly...`, `orderItemSnapshotIsImmutable` |
| Testcontainers PostgreSQL + RabbitMQ | `OutboxPublisherIT`, `ReliableConsumerIT`, `Inventory*IT`, `Payment*IT`/`WalletIT`, `CartIT`, `CheckoutSagaIT` |
| Checkout có JWT; từ chối request không xác thực | `CheckoutSagaIT.checkoutRejectsUnauthenticatedRequests`, `GatewayIT.protectedRoutesReject...`, `AuthIT.protectedEndpointRejects...` |
| UserRegistered trùng không cấp credit 2 lần | `PaymentMessagingIT.duplicateUserRegisteredDeliveriesGrantInitialCreditOnce`, `WalletIT.initialCreditIsGrantedExactlyOnceEvenUnderConcurrentDuplicates` |
| Reserve hàng đồng thời không oversell | `InventoryReservationIT.concurrentReservationsCannotOversell` (+ thứ tự khóa ngược nhau) |
| Reserve credit đồng thời không overspend | `WalletIT.concurrentReservationsCannotOverspendAvailableBalance` |
| Checkout trùng không tạo đơn trùng | `CheckoutSagaIT.sameIdempotencyKeyReturnsSameOrderEvenUnderConcurrency`, `differentKeysForSameCartRevision...` |
| Message RabbitMQ trùng không lặp hiệu ứng | `ReliableConsumerIT.duplicateDelivery...`, `InventoryMessagingIT`, `CartIT.orderConfirmedRemovesOnly...`, `CheckoutSagaIT.duplicateReplyMessages...` |
| Outbox phục hồi sau lỗi | `OutboxPublisherIT.failedPublishIsRecordedWithBackoffAndRecoveredLater`, `crashAfterBrokerConfirm...`, `concurrentPublishersNeverSendTheSameRowTwice` |
| Retry có giới hạn, hết lượt vào DLQ | `ReliableConsumerIT.transientFailuresAreRetriedBoundedTimesThenDeadLettered`, `permanentFailureGoesStraightToDlq...`, `redrivenMessage...` |
| Circuit breaker mở theo cấu hình | `ResilientCallerTest.circuitOpensAfterFailureThreshold...`, `breakersAreIsolatedPerDependency`; demo C |
| Bồi hoàn khi inventory/credit thất bại | `CheckoutSagaIT.creditRejectionReleasesInventoryAndFailsOrder`, `inventoryRejectionFails...`, `reserveTimeoutsExhaustedCompensate...` |
| Restart giữa Saga vẫn tiếp tục | `CheckoutSagaIT.sagaResumesFromPersistedStateWhenRepliesAreMissing`; demo A (payment-service dừng/khởi động lại) |
| Timeout sau side effect, retry an toàn | `WalletIT.reserveResentAfterTimeoutDoesNotReserveTwice`, `InventoryReservationIT.duplicateReserveCommands...` |
| Capture thành công nhưng Order chết trước khi ghi nhận | `CheckoutSagaIT.captureSucceededButReplyLost_watcherResendsOnlyCaptureAndOrderConfirms` + `WalletIT.captureSucceedsButReplyIsLost_resendIsSafeAndChargesOnce` |

Test tránh `sleep` dài: thời gian Saga/outbox được điều khiển bằng `MutableClock`; chờ bất đồng bộ dùng Awaitility
(poll tới khi điều kiện đúng, timeout 10–15s). Retry delay trong test là 50–200ms.

## 8. Chưa được kiểm chứng tự động

- Chạy nhiều instance của cùng một service trong Docker Compose (thiết kế hỗ trợ nhờ `SKIP LOCKED`; chỉ có test đa luồng
  trong một JVM cho publisher).
- Restart RabbitMQ khi có message đang bay (durable/persistent được cấu hình nhưng chưa có test tự động).
- Tải cao / hiệu năng.

## 9. Bổ sung: Swagger/OpenAPI + giao diện Next.js (2026-10-21)

### Swagger

- springdoc-openapi 2.8.17 (dòng cho Spring Boot 3.5) trong `common-web` (`OpenApiConfig`) và `springdoc-openapi-starter-webflux-ui` ở gateway.
- Test mới: `CheckoutSagaIT.openApiSpecAndSwaggerUiArePublicAndDescribeBearerAuth`,
  `GatewayIT.swaggerUiIsServedAndServiceSpecsAreProxiedWithoutToken`.
- Kiểm tra trên stack Docker:
  - `GET :8080/swagger-ui.html` → 302 → `/swagger-ui/index.html`; `/v3/api-docs/swagger-config` liệt kê 6 spec.
  - Spec của cả 6 service tải được qua `/docs/<service>/v3/api-docs`, có `bearerAuth`, server `/`.
  - Checkout trong spec: header `Idempotency-Key` (required), response 202/200/409/422/503; tham số `Jwt` không bị lộ.
  - Bấm thật "Try it out" bằng trình duyệt headless: Swagger tổng hợp ở gateway → `GET http://localhost:8080/api/products` → **200**;
    Swagger của order-service + Authorize token ADMIN → `GET http://localhost:8086/admin/sagas/attention` → **200**.
- Lỗi phát hiện và sửa: phiên bản đầu khai báo server tuyệt đối (gateway trước) ⇒ Swagger mở ở port service sẽ gọi sang
  origin khác (bị CORS chặn). Đổi sang server tương đối `/`.
- Lưu ý vận hành phát hiện được: `./mvnw install` (không `clean`) KHÔNG đóng gói lại jar của service khi chỉ `libs/*` thay đổi
  (5/6 service vẫn chạy spec cũ). Dùng `clean` — đã ghi vào README "Xử lý sự cố".

### Giao diện `webshop-ui` (Next.js 16.4.2, React 19, Tailwind 4)

- `npx eslint src` sạch; `npx tsc --noEmit` sạch; `next build` thành công (sau khi sửa 2 lỗi prerender của Cache Components:
  `crypto.randomUUID()`/`Math.random()` lúc render → chuyển vào event handler; `usePathname()` ở layout → bọc `<Suspense>`).
- Docker: `docker compose up -d --build webshop-ui` → container healthy; BFF gọi được gateway và `/actuator/health` của service;
  BFF chặn `/bff/payment/api/...` (403 FORBIDDEN_PATH) — chỉ cho `/admin/**` và `/actuator/**`.
- Kiểm thử bằng trình duyệt headless (gstack browse), cả với `next start` local lẫn container:
  - Đăng ký (email ngẫu nhiên) → tự đăng nhập → thanh điều hướng hiện ví 10.000.000 ₫.
  - Thêm 2 Keyboard + 1 Mouse → giỏ 3.450.000 ₫ → Đặt hàng → trang theo dõi Saga: 4 bước xanh, CONFIRMED, ví 6.550.000 ₫.
  - Trang *Kịch bản demo*: cả 6 kịch bản **ĐẠT** (mua thành công; thiếu credit → bồi hoàn; hết hàng; 3 request cùng key →
    `202, 200, 200` cùng 1 orderId; 2 tab khác key → `202 & 409`; bảo mật/validation 401/401/400/422/400).
  - Trang *Quản trị*: health 6 service UP, outbox backlog 0, 3 circuit breaker CLOSED, "Gửi message hỏng" → message mới vào
    `payment.commands.q.dlq` với `UNPARSEABLE`; token không phải ADMIN gọi `/bff-tools/poison` → **403**.
  - Mobile 390×844: không có cuộn ngang; nhật ký Saga (token ADMIN) hiển thị đường bồi hoàn đầy đủ.
  - Console trình duyệt: không có lỗi ngoài các response 4xx do kịch bản 6 cố ý tạo ra.
- Chưa có test tự động cho UI (Playwright/Vitest) — kiểm thử ở trên là thủ công qua trình duyệt headless.
