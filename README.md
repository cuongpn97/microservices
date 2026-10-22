# Webshop Microservices — dự án học tập

Một webshop gồm 7 ứng dụng Spring Boot độc lập, được xây dựng để **đọc code và hiểu vì sao mỗi pattern tồn tại**:
Database-per-service, API Gateway, JWT (defense in depth), Transactional Outbox, Inbox/idempotency, Saga orchestration
có bồi hoàn, retry có backoff + jitter, DLQ, circuit breaker, khóa/cập nhật nguyên tử chống oversell/overspend.

> **Giao diện demo:** http://localhost:3000 (Next.js, thư mục [`webshop-ui/`](webshop-ui/README.md)) ·
> **Swagger UI tổng hợp:** http://localhost:8080/swagger-ui.html
>
> Tài liệu chi tiết:
> [Kiến trúc + sơ đồ Mermaid](docs/architecture.md) ·
> [Quyết định thiết kế](docs/design-decisions.md) ·
> [Kịch bản lỗi & phục hồi](docs/failure-scenarios.md) ·
> [API](docs/api.md) ·
> [Phiên bản phụ thuộc](docs/dependency-compatibility.md) ·
> [Báo cáo kiểm chứng](docs/verification-report.md)

## Cấu trúc monorepo

```
.
├── pom.xml                       # Parent: Spring Boot 3.5.16, Spring Cloud 2025.0.3, Resilience4j 2.3.0
├── mvnw, mvnw.cmd, .mvn/         # Maven Wrapper (Maven 3.9.16) — không cần cài Maven
├── libs/
│   ├── common-web/               # correlation-id, error model, JWT decoder, Resilience4j HTTP caller
│   └── common-messaging/         # envelope, Outbox, Inbox, reliable consumer, retry/DLQ topology, DLQ admin
├── api-gateway/        :8080     # Spring Cloud Gateway (WebFlux)
├── auth-service/       :8081     # auth_db
├── product-service/    :8082     # product_db
├── cart-service/       :8083     # cart_db
├── inventory-service/  :8084     # inventory_db
├── payment-service/    :8085     # payment_db
├── order-service/      :8086     # order_db — Saga orchestrator
├── search-service/     :8087     # Elasticsearch read model, dựng từ CDC (profile "search")
├── infra/postgres/init/          # tạo 6 database + 6 user (không quyền chéo) + user CDC
├── infra/debezium/               # cấu hình connector Debezium + script đăng ký
├── docker-compose.yml, .env.example
├── webshop-ui/         :3000     # Giao diện demo Next.js 16 (BFF proxy, theo dõi Saga, trang quản trị)
├── scripts/                      # demo.sh, demo-failures.sh (bản dòng lệnh của các kịch bản)
├── http/                         # webshop.http (IntelliJ/VS Code), Postman collection
└── docs/
```

Mỗi service: `src/main/resources/db/migration` (Flyway), `Dockerfile`, cấu hình qua biến môi trường.

## Yêu cầu

- JDK 21+ (đã kiểm tra với JDK 25 build `--release 21`)
- Docker Desktop / Docker Engine + Compose v2 (cần cho cả integration test — Testcontainers)
- `bash` + `curl` + (`jq` hoặc `python3`) để chạy script demo (Windows: Git Bash)

## Khởi động nhanh

```bash
cp .env.example .env                       # giá trị LOCAL-ONLY, file .env bị .gitignore
./mvnw -B package -DskipTests              # build 7 jar (Windows PowerShell: .\mvnw.cmd ...)
docker compose up -d --build               # postgres, rabbitmq + 7 service
docker compose ps                          # đợi tất cả (healthy) ~1-2 phút
```

Sau đó mở:

| URL | Dùng để |
|---|---|
| http://localhost:3000 | **Giao diện demo**: đăng ký → mua hàng → xem Saga chạy từng bước; trang *Kịch bản demo* chạy tự động 6 tình huống; trang *Quản trị* (đăng nhập bằng `ADMIN_EMAIL`/`ADMIN_PASSWORD` trong `.env`) |
| http://localhost:8080/swagger-ui.html | **Swagger UI tổng hợp** qua gateway — chọn service ở ô *Select a definition* |
| http://localhost:808x/swagger-ui.html | Swagger UI của từng service (8081–8086) — dùng để thử các endpoint `/admin/**` |
| http://localhost:15672 | RabbitMQ Management UI |

Bản dòng lệnh của các kịch bản (không cần trình duyệt): `bash scripts/demo.sh`, `bash scripts/demo-failures.sh`.

### Thử API bằng Swagger

1. Mở http://localhost:8080/swagger-ui.html, chọn `auth-service`, gọi `POST /api/auth/register` rồi `POST /api/auth/login`.
2. Copy `accessToken`, bấm **Authorize** (góc phải), dán token (không cần chữ `Bearer`) → **Authorize**.
   Token được giữ khi chuyển sang service khác (`persistAuthorization`).
3. Chọn `cart-service` → `POST /api/cart/items`; chọn `order-service` → `POST /api/orders/checkout`
   (nhập `Idempotency-Key`, ví dụ `checkout-001`), rồi `GET /api/orders/{orderId}` để poll.
4. Endpoint vận hành `/admin/**`: mở Swagger UI **của chính service** (ví dụ http://localhost:8086/swagger-ui.html),
   Authorize bằng token ADMIN. Gateway cố ý không route `/admin`.

Mỗi spec dùng server tương đối `/` nên "Try it out" luôn gửi về đúng origin đang mở Swagger (không bị CORS chặn).

Dừng: `docker compose down` (giữ dữ liệu) hoặc `docker compose down -v` (xóa volume, migration chạy lại từ đầu).

### Search (CDC: Debezium → Kafka → Elasticsearch)

Tách thành profile vì cần thêm ~2.5GB RAM (Kafka, Kafka Connect, Elasticsearch):

```bash
./mvnw -B package -DskipTests
docker compose --profile search up -d --build
docker compose logs connect-init                  # connector đã đăng ký, trạng thái RUNNING
curl -s 'localhost:8080/api/search/products?q=ban%20phim'
```

Trên UI, ô tìm kiếm ở trang Cửa hàng gọi search-service. Thử sửa giá (Swagger, ADMIN) rồi tìm lại: kết quả
đổi sau khoảng 1–2 giây — đó là độ trễ của CDC.

| URL | Dùng để |
|---|---|
| http://localhost:8090/connectors?expand=status | REST API Kafka Connect: trạng thái connector |
| http://localhost:9200/products/_search?pretty | Xem trực tiếp document trong Elasticsearch |
| `localhost:29092` | Kafka từ máy host (ví dụ `kafka-console-consumer.sh --topic cdc.product.public.products`) |

**Volume PostgreSQL đã có từ trước** (script init không chạy lại): tạo user CDC một lần **trước** khi chạy profile
`search`, khi đó migration mới của product-service tự GRANT quyền đọc bảng:

```bash
docker compose up -d postgres
docker compose exec postgres psql -U postgres -c "CREATE ROLE cdc_user WITH LOGIN REPLICATION PASSWORD 'local-only-cdc-db-pw'" \
  -c "GRANT CONNECT ON DATABASE product_db TO cdc_user"
```

Nếu product-service đã chạy migration CDC trước khi có user (GRANT bị bỏ qua), cấp quyền tay:

```bash
docker compose exec postgres psql -U postgres -d product_db -c "GRANT USAGE ON SCHEMA public TO cdc_user; GRANT SELECT ON products TO cdc_user"
```

**Nâng cấp từ bản có index tồn kho** (connector `webshop-inventory`): `connect-init` tự gỡ connector, nhưng
replication slot vẫn còn và **giữ WAL của cả PostgreSQL server** (tới trần `max_slot_wal_keep_size`) — phải drop tay:

```bash
docker compose exec postgres psql -U postgres -c "SELECT pg_drop_replication_slot('dbz_inventory')"
docker compose exec postgres psql -U postgres -c "SELECT slot_name, active, wal_status FROM pg_replication_slots"
```

(Hoặc `docker compose down -v` để làm lại từ đầu.) Kiến trúc và trade-off:
[architecture.md mục 10](docs/architecture.md#10-search-cdc--kafka--elasticsearch-profile-search),
[design-decisions.md mục 12](docs/design-decisions.md#12-search-cdc-debezium--kafka--elasticsearch-thay-vì-gọi-đồng-bộ-hoặc-dùng-outbox-sẵn-có).

### Migrations

Flyway chạy tự động khi mỗi service khởi động (`spring.flyway.enabled=true`, `ddl-auto=validate` — Hibernate không bao giờ
tự sửa schema). Script khởi tạo PostgreSQL chỉ tạo database/user; mọi bảng do migration của service sở hữu.
Inventory có thêm dữ liệu demo ở `db/demo` (bật mặc định qua `FLYWAY_LOCATIONS`; đặt
`FLYWAY_LOCATIONS=classpath:db/migration` để tắt).

### Chạy một service ngoài Docker (debug trong IDE)

```bash
docker compose up -d postgres rabbitmq
set -a; . ./.env; set +a
./mvnw -B -pl order-service -am install -DskipTests      # cài các thư viện libs/* vào ~/.m2
DB_PASSWORD=$ORDER_DB_PASSWORD ./mvnw -f order-service/pom.xml spring-boot:run
```

Biến cần thiết: `DB_PASSWORD`, `RABBITMQ_PASSWORD`, `JWT_SECRET` (không có giá trị mặc định trong code).

## Test

```bash
./mvnw -B verify                                   # toàn bộ reactor: unit + integration (Testcontainers)
./mvnw -B -pl order-service -am verify             # một service (+ thư viện nó phụ thuộc)
./mvnw -B -pl payment-service -am verify -Dtest=WalletIT -Dsurefire.failIfNoSpecifiedTests=false
```

Test đáng đọc nhất:

| Test | Chứng minh |
|---|---|
| `OutboxPublisherIT` | Outbox phục hồi sau lỗi publish; crash sau confirm ⇒ consumer dedup; `SKIP LOCKED` không gửi trùng |
| `ReliableConsumerIT` | Dedup Inbox; retry đúng số lần rồi vào DLQ kèm header; poison message; redrive giữ idempotency |
| `ResilientCallerTest` | Circuit breaker mở theo cấu hình, fail-fast; breaker riêng từng dependency; timeout; 4xx không retry |
| `InventoryReservationIT` | 30 người mua đồng thời 10 sản phẩm ⇒ đúng 10; không deadlock; tombstone; CHECK constraint |
| `WalletIT` | Credit ban đầu chỉ 1 lần; 25 reserve đồng thời không vượt số dư; capture gửi lại không trừ 2 lần; ledger append-only |
| `CheckoutSagaTest` | Máy trạng thái Saga thuần (không DB): bồi hoàn, timeout, reply trễ/trùng |
| `CheckoutSagaIT` | Saga end-to-end qua RabbitMQ thật; checkout trùng; phục hồi sau restart; capture thành công nhưng reply mất |
| `GatewayIT` | JWT ở biên, CORS, giới hạn kích thước, không lộ `/admin` |
| `SearchCdcIT` | Tìm không dấu và theo tiền tố; index mới dựng sau alias, chỉ chuyển alias khi đã bắt kịp; bản cũ replay không ghi đè bản mới; xóa không "hồi sinh"; message hỏng vào DLT mà không chặn partition |

## Endpoint chính (qua gateway :8080)

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | public |
| GET | `/api/products`, `/api/products/{id}`, `/api/inventory/{productId}`, `/api/inventory/batch` | public |
| GET | `/api/search/products?q=&minPriceVnd=&maxPriceVnd=&sort=` (profile `search`) | public |
| GET/POST/PUT/DELETE | `/api/cart`, `/api/cart/items[/{productId}]` | USER |
| POST | `/api/orders/checkout` (header `Idempotency-Key`) | USER |
| GET | `/api/orders`, `/api/orders/{id}` | USER |
| GET | `/api/wallet/me`, `/api/wallet/me/ledger` | USER |
| PUT | `/api/inventory/{productId}`; POST/PUT `/api/products` | ADMIN |

Admin/vận hành (gọi thẳng port service, cần token ADMIN — `ADMIN_EMAIL`/`ADMIN_PASSWORD` trong `.env`):
`/admin/dlq` (8083–8086), `/admin/sagas/{id}` (8086), `/admin/reservations/{id}` (8084), `/admin/credit-reservations/{id}` (8085).
Chi tiết, ví dụ request/response: [docs/api.md](docs/api.md).

## Luồng demo (curl thuần)

```bash
GW=http://localhost:8080
curl -s $GW/api/auth/register -H 'content-type: application/json' \
  -d '{"email":"bob@example.com","password":"bob-password-1","fullName":"Bob"}'
TOKEN=$(curl -s $GW/api/auth/login -H 'content-type: application/json' \
  -d '{"email":"bob@example.com","password":"bob-password-1"}' | jq -r .accessToken)
curl -s $GW/api/wallet/me -H "Authorization: Bearer $TOKEN"                 # 10.000.000 VND
curl -s $GW/api/products
curl -s $GW/api/cart/items -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"productId":"11111111-1111-1111-1111-111111111111","quantity":2}'
ORDER=$(curl -s -X POST $GW/api/orders/checkout -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: checkout-$(date +%s)" | jq -r .orderId)
curl -s $GW/api/orders/$ORDER -H "Authorization: Bearer $TOKEN"              # PENDING → CONFIRMED
curl -s $GW/api/wallet/me -H "Authorization: Bearer $TOKEN"                 # 7.000.000 VND
```

Thiết lập tồn kho (ADMIN):

```bash
ADMIN=$(curl -s $GW/api/auth/login -H 'content-type: application/json' \
  -d '{"email":"admin@webshop.local","password":"local-only-admin-pw"}' | jq -r .accessToken)
curl -s -X PUT $GW/api/inventory/22222222-2222-2222-2222-222222222222 -H "Authorization: Bearer $ADMIN" \
  -H 'content-type: application/json' -d '{"stockOnHand":200}'
```

## Quan sát

- Health: `curl localhost:8086/actuator/health` (mọi service có `/actuator/health/{liveness,readiness}`).
- Metric Prometheus: `curl -s localhost:8086/actuator/prometheus | grep -E 'webshop_|resilience4j'`.
- RabbitMQ UI: http://localhost:15672 (user/pass trong `.env`) — xem queue, `*.retry.n`, `*.dlq`.
- Log JSON có `correlationId` + `sagaId`: `docker compose logs -f order-service payment-service | grep <orderId>`.

## Xử lý sự cố

| Triệu chứng | Nguyên nhân thường gặp / cách xử lý |
|---|---|
| Service không lên, log `JWT_SECRET ... at least 32 bytes` | Chưa tạo `.env` hoặc secret quá ngắn. |
| `password authentication failed for user "x_user"` | Volume PostgreSQL cũ được tạo với mật khẩu khác. `docker compose down -v` rồi up lại. |
| Port 5432/5672/8080… đã bị chiếm | Dừng tiến trình đang chiếm hoặc đổi cột `ports:` trong `docker-compose.yml`. |
| Test lỗi `Could not find a valid Docker environment` | Docker Desktop chưa chạy (Testcontainers cần Docker). |
| `GET /api/wallet/me` → 404 ngay sau đăng ký | Bình thường (eventual consistency); thử lại sau ~1 giây. Nếu kéo dài: kiểm tra `payment.user-registered.q.dlq`. |
| Đơn đứng ở `PENDING` lâu | Xem `GET localhost:8086/admin/sagas/{id}` (state, attempts, deadline); kiểm tra service đích và DLQ. |
| `webshop_outbox_backlog` tăng mãi | Broker down, hoặc message không route được (`last_error` trong `outbox_event` có `NO_ROUTE`) — thiếu queue/binding. |
| Gateway 413 | Body > 1MB (`GATEWAY_MAX_REQUEST_SIZE`). |
| Trên Windows `bash scripts/demo.sh` lỗi `\r` | Đảm bảo file dùng LF (repo lưu LF; tắt `core.autocrlf` cho repo nếu clone lại). |

## Đã hoàn thành / Giới hạn đã biết

Hoàn thành: cả 6 phase của yêu cầu — 7 service, migration, JWT + gateway, Outbox/Inbox/DLQ, Saga + bồi hoàn + timeout
+ phục hồi, circuit breaker/retry, test (92 test tự động), Docker Compose, tài liệu, demo chạy thật (xem báo cáo kiểm chứng).

Giới hạn và khoảng trống (có chủ đích hoặc ngoài phạm vi):

1. **At-least-once, không exactly-once**: mọi consumer idempotent; không có đảm bảo exactly-once toàn cục.
2. **JWT HS256 shared secret**; chưa có refresh token, revocation, JWKS/RS256 (khuyến nghị cho production).
3. **Reservation không tự hết hạn** ở Inventory/Payment: đảm bảo bước confirm luôn thành công, nhưng nếu Order Service
   ngừng vĩnh viễn thì tài nguyên bị giữ tới khi xử lý thủ công. Chưa có job đối soát tự động (chỉ có SQL kiểm tra +
   endpoint admin, xem failure-scenarios §4).
4. **Không có refund**: credit đã capture không hoàn lại được (đúng phạm vi: không có tiền thật, không hủy đơn sau confirm).
5. **Không publish `OrderFailed`** vì chưa có consumer (xem design-decisions §4).
6. **Bảng `processed_message` chưa có job dọn**; outbox có job xóa bản ghi đã publish > 7 ngày.
7. **Thứ tự message không tuyệt đối** khi publish lỗi rồi thử lại; Saga chịu được đảo thứ tự.
8. Circuit breaker chỉ cho HTTP; consumer RabbitMQ không có "breaker" (dùng retry queue + DLQ).
9. Một PostgreSQL server và một RabbitMQ node cho local — không HA. Không Kubernetes/service mesh (theo yêu cầu).
10. `/actuator/prometheus` mở public trên port service (chỉ phù hợp mạng nội bộ); gateway không route `/actuator` của service.
11. Kịch bản Postman dùng `setNextRequest` để poll, không có độ trễ thật giữa các lần poll.
12. `webshop-ui` lưu access token trong `localStorage` (chỉ hợp với demo; production dùng cookie HttpOnly do BFF quản lý).
    UI chưa có test tự động (đã kiểm thử bằng trình duyệt headless, xem báo cáo kiểm chứng).
