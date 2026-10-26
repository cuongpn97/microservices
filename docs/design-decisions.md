# Quyết định thiết kế

Mỗi mục trả lời: **vì sao dùng**, **giải quyết vấn đề gì**, **KHÔNG giải quyết gì**, **chi phí vận hành**.

---

## 1. Database-per-service (một PostgreSQL server, bảy database)

- **Vì sao**: mỗi service tự quyết schema, migration và vòng đời dữ liệu; không service nào JOIN/UPDATE dữ liệu của service khác.
- **Giải quyết**: coupling qua schema (đổi cột ở service A làm vỡ service B), "distributed monolith".
- **Không giải quyết**: cô lập tài nguyên vật lý — sáu database vẫn chung CPU/IO/disk của một server; server chết thì cả hệ chết. Production nên tách server/cluster cho service quan trọng.
- **Cách thực thi**: `infra/postgres/init/01-create-databases.sh` tạo user riêng là OWNER của database riêng và `REVOKE ALL ... FROM PUBLIC` → user `cart_user` không thể CONNECT vào `payment_db`.
- **Chi phí**: không JOIN chéo service; báo cáo tổng hợp cần pipeline riêng (CDC/ETL), ngoài phạm vi.

## 2. Spring Cloud Gateway (WebFlux) chỉ làm việc của biên

- **Vì sao**: một điểm vào để áp dụng CORS, giới hạn kích thước request, xác thực sớm, correlation-id.
- **Không làm**: nghiệp vụ, điều phối giao dịch, retry. Retry ở gateway sẽ nhân số request khi downstream đang quá tải và có thể lặp lại POST không idempotent.
- **Không phải ranh giới bảo mật duy nhất**: service vẫn tự verify JWT (defense in depth). Trong Docker network, ai vào được mạng nội bộ có thể gọi thẳng `order-service:8086` — và vẫn phải có JWT hợp lệ.
- **Vì sao gateway không dùng `common-web`**: thư viện đó kéo Spring MVC (Servlet); có cả MVC và WebFlux trên classpath thì Spring Boot chọn Servlet → gateway hỏng. Gateway có cấu hình JWT reactive riêng.

## 3. JWT HS256 với shared secret (local) — RS256/ES256 + JWKS (production)

- **Hiện tại**: Auth ký HS256; mọi service verify bằng cùng secret (`JWT_SECRET`, ≥ 32 byte, từ env).
- **Nhược điểm HS256**: ai có secret để verify cũng có thể **ký** token. Mỗi service giữ secret = bề mặt rò rỉ lớn.
- **Production khuyến nghị**: Auth giữ private key (RS256/ES256), publish public key qua endpoint JWKS (`/.well-known/jwks.json`); service dùng `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`. Xoay vòng key bằng `kid`. Cân nhắc dùng IdP chuẩn (Keycloak, Auth0...).
- **Chưa có**: refresh token, thu hồi token (revocation), logout phía server. Access token sống 30 phút (cấu hình được).

## 4. Transactional Outbox + polling publisher

- **Vấn đề**: "ghi DB rồi publish message" là hai hệ thống; crash ở giữa → mất event hoặc event "ma" (DB rollback nhưng message đã đi). XA/2PC bị loại vì phức tạp và RabbitMQ không hỗ trợ tốt.
- **Giải pháp**: event được INSERT vào `outbox_event` trong cùng transaction với thay đổi nghiệp vụ (`OutboxWriter` bắt buộc `Propagation.MANDATORY`). Publisher riêng đọc và gửi.
- **Claim an toàn**: `SELECT ... FOR UPDATE SKIP LOCKED` → nhiều instance không gửi trùng cùng một dòng (test `concurrentPublishersNeverSendTheSameRowTwice`).
- **Publisher confirms + mandatory**: chỉ đánh dấu `published_at` khi broker ack và message không bị return (unroutable).
  Hệ quả có chủ đích: chỉ publish event/command đã có queue đích. Ban đầu Order Service publish `OrderFailed` nhưng
  không ai subscribe → mọi bản ghi kẹt trong outbox (phát hiện nhờ metric `webshop.outbox.backlog` khi chạy demo) → đã bỏ.
  Không dùng alternate exchange để "nuốt" message không route được, vì như vậy command gửi tới một queue chưa được
  khai báo (service đích chưa khởi động lần nào) sẽ biến mất thay vì được gửi lại.
- **Không giải quyết**:
  - Exactly-once: crash sau khi broker ack nhưng trước khi commit `published_at` → gửi lại. Hệ thống là **at-least-once**, consumer bắt buộc idempotent (test `crashAfterBrokerConfirmCausesRedeliveryWhichConsumerDeduplicates`).
  - Thứ tự tuyệt đối: dòng lỗi được thử lại sau có thể bị dòng mới vượt. Consumer (Saga) kiểm tra state nên chịu được đảo thứ tự.
  - Độ trễ: polling 500ms → thêm tối đa ~0,5s độ trễ. CDC (Debezium đọc WAL) cho độ trễ thấp hơn nhưng thêm hạ tầng.
- **Chi phí**: bảng outbox phải dọn (job xóa bản ghi đã publish > 7 ngày); giữ lock trong lúc chờ confirm (batch nhỏ, timeout 5s); thêm truy vấn định kỳ lên DB.

## 5. Inbox (processed_message) + unique constraint nghiệp vụ

- **Vấn đề**: at-least-once nghĩa là message có thể đến 2+ lần (redelivery sau khi ack mất, outbox gửi lại, operator redrive).
- **Inbox**: `INSERT ... ON CONFLICT DO NOTHING` vào `processed_message(consumer, message_id)` **trong cùng transaction** với hiệu ứng nghiệp vụ. Commit cả hai hoặc không gì cả. Ack chỉ sau commit.
- **Vì sao vẫn cần khóa nghiệp vụ**: Inbox chỉ chặn trùng theo `eventId`. Saga gửi lại `ReserveCredit` sau timeout là message MỚI (eventId mới) nhưng cùng ý nghĩa. Vì vậy `credit_reservation.id = orderId`, `reservation.id = orderId`, `wallet_ledger.idempotency_key` UNIQUE, `wallet.user_id` UNIQUE — chốt chặn cuối ở DB.
- **Chi phí**: bảng inbox lớn dần — cần job dọn theo thời gian lưu > thời gian tối đa một message có thể bị giao lại (chưa cài job, ghi trong Known gaps).

## 6. Saga orchestration (thay vì choreography hoặc 2PC)

- **Vì sao orchestration**: luồng checkout có nhiều bước, nhánh bồi hoàn và timeout; đặt toàn bộ ở một máy trạng thái (`CheckoutSaga`) dễ đọc, test và vận hành hơn là suy luận từ chuỗi event rải rác (choreography).
- **Thiết kế để loại bỏ cửa sổ nguy hiểm**:
  1. Đơn + Saga + command đầu tiên ghi **nguyên tử** (outbox) — không có "đơn tồn tại nhưng Saga không bao giờ chạy".
  2. Giữ (reserve) cả hai tài nguyên **trước** khi xác nhận; capture là bước cuối.
  3. `CONFIRMING` là **điểm không quay đầu**: chỉ vào khi cả hai reservation đã giữ chắc; participant không tự hết hạn reservation nên commit/capture không thể thất bại vì nghiệp vụ → chỉ forward recovery (gửi lại tới khi xong), không bao giờ bồi hoàn sau khi đã quyết định confirm. Nhờ đó tránh tình huống đã trừ tiền rồi lại phải hoàn tiền.
  4. Timeout ≠ thất bại: không biết participant đã làm hay chưa → gửi lại cùng khóa; khi bỏ cuộc thì release mọi thứ **có thể** đang giữ.
  5. Tombstone: release đến trước reserve → reserve muộn bị từ chối.
  6. Đơn chỉ chuyển `FAILED` khi mọi release đã được xác nhận; compensation lỗi thì Saga ở `COMPENSATING` (có thể phục hồi), không giả vờ đã xong.
- **Trade-off**:
  - Giữ hàng/credit trong suốt Saga → tài nguyên bị khóa tạm thời (vài trăm ms bình thường; tối đa vài phút khi sự cố). Không có hết hạn tự động phía participant — đổi lại là đảm bảo confirm luôn thành công. Hệ thống thật thường thêm "reservation expiry" kèm cơ chế đối soát.
  - Không có isolation giữa các Saga (đặc tính chung của Saga): người dùng có thể thấy tồn kho "available" giảm rồi tăng lại khi một đơn khác bị bồi hoàn.
  - Order Service là điểm điều phối; nếu down, Saga dừng (nhưng không mất) và tiếp tục khi lên lại.

## 7. Khóa và cập nhật nguyên tử (Inventory, Payment)

- **Inventory**: `SELECT ... FOR UPDATE` các dòng tồn kho **theo thứ tự product_id** (tránh deadlock khi hai đơn chứa A,B theo thứ tự khác nhau), kiểm tra đủ hàng cho **tất cả** dòng rồi mới giữ (all-or-nothing). CHECK `reserved_quantity <= stock_on_hand`.
- **Payment**: một câu lệnh điều kiện `UPDATE wallet SET reserved = reserved + :amt WHERE balance - reserved >= :amt` — kiểm tra và ghi là một thao tác nguyên tử, không có khoảng "đọc rồi ghi". CHECK `reserved_vnd <= balance_vnd` (available ≥ 0). Ledger append-only bằng trigger cấm UPDATE/DELETE.
- **Vì sao Payment dùng JDBC thuần**: với tiền, câu SQL chính là đặc tả; nhìn thấy rõ điều kiện WHERE quan trọng hơn sự tiện lợi của ORM. Các service khác dùng JPA để so sánh hai phong cách.
- **Tiền tệ**: `BIGINT`/`long` VND ở mọi nơi; phép nhân/cộng dùng `Math.multiplyExact/addExact` để tràn số ném lỗi thay vì sai âm thầm.

## 8. Resilience4j cho HTTP đồng bộ

- **Timeout** (connect 1s, read 2s): không có timeout thì một dependency chậm giữ hết thread của service gọi.
- **Circuit breaker riêng từng dependency** (`product-service`, `cart-service`): sliding window 20 lời gọi, tối thiểu 10, ngưỡng lỗi 50%, slow-call 2s, OPEN 10s, HALF_OPEN cho 3 lời gọi thử. Một dependency hỏng không làm "mở cầu dao" cho dependency khác (test `breakersAreIsolatedPerDependency`).
- **Retry** nằm ngoài breaker, chỉ cho GET, chỉ cho `ResourceAccessException`/5xx; 4xx không retry, không tính lỗi.
- **Fallback có ý nghĩa**: Cart trả giỏ không kèm giá (`pricingAvailable=false`); checkout trả 503 `DEPENDENCY_UNAVAILABLE` (client retry với cùng Idempotency-Key).
- **Không thay thế**: durable messaging, Saga, compensation. Breaker chỉ giúp fail-fast và giảm tải cho dependency đang hỏng.
- **Vì sao không áp dụng cho RabbitMQ consumer**: breaker bọc lời gọi đi ra (outbound). Consumer nhận message do broker đẩy vào; lỗi khi xử lý được điều tiết bằng ack/nack, prefetch, retry queue và DLQ. Muốn "ngắt" consumer khi DB down cần cơ chế khác (dừng listener container, giảm concurrency) — không tự có từ cấu hình HTTP.

## 9. Retry bất đồng bộ bằng retry queue có TTL + DLQ

- **Vì sao không `requeue=true`**: requeue ngay lập tức tạo vòng lặp nóng với poison message, đốt CPU và chặn queue.
- **Vì sao không retry trong bộ nhớ** (Spring Retry interceptor): giữ message và thread trong lúc chờ; restart là mất bộ đếm.
- **Thiết kế**: lỗi tạm thời → publish (có confirm) sang `Q.retry.n` với `expiration` = backoff ± jitter, ack bản gốc; hết TTL broker dead-letter về `Q`. Header `x-attempt` đếm số lần. Mỗi tầng một queue để giảm head-of-line blocking của per-message TTL.
- **Lỗi vĩnh viễn** (JSON hỏng, version không hỗ trợ, `PermanentMessageException`) → DLQ ngay.
- **DLQ giữ**: messageId gốc, body gốc, `x-original-routing-key`, `x-original-exchange`, `x-original-queue`, `x-attempt`, `x-failure-reason`, `x-last-error`, `x-failed-at`, correlationId (AMQP property) và correlationId/sagaId trong envelope.
- **Chi phí**: nhiều queue hơn (mỗi queue chính +3 retry +1 DLQ); jitter trong một tầng vẫn có thể gây chặn đầu hàng ngắn.

## 10. Thư viện dùng chung vs. độc lập triển khai

- `common-web`, `common-messaging` là thư viện có version, được đóng gói vào jar của từng service. Mỗi service vẫn build, chạy, deploy độc lập (`./mvnw -pl order-service -am package`).
- **Rủi ro**: thay đổi thư viện có thể buộc rebuild nhiều service. Giảm thiểu: thư viện chỉ chứa hạ tầng (outbox, inbox, retry), KHÔNG chứa domain model; payload message mỗi service tự định nghĩa (tolerant reader: bỏ qua field lạ, kiểm tra `eventVersion`).

## 11. Không dùng service discovery (Eureka)

URL các service cấu hình qua biến môi trường (`PRODUCT_SERVICE_URL`, ...). Docker Compose cung cấp DNS nội bộ. Trên nền tảng thật, DNS của nền tảng (hoặc service mesh) thường thay thế Eureka.

## 12. Search: CDC (Debezium → Kafka) → Elasticsearch, thay vì gọi đồng bộ hoặc dùng Outbox sẵn có

- **Vấn đề**: tìm sản phẩm full-text, không dấu, gõ dở, lọc/sắp xếp theo giá. PostgreSQL `LIKE` không làm được
  (không dấu, độ liên quan, chịu gõ sai) mà vẫn nhanh khi dữ liệu lớn.
- **Thiết kế**: search-service giữ một **read model** (CQRS) trong Elasticsearch. Debezium đọc WAL của PostgreSQL
  (logical decoding, `pgoutput`) cho đúng một bảng → Kafka → search-service upsert vào index. Ban đầu là bảng `products`
  thô; từ khi tách SPU/SKU là bảng `catalog_document` — xem mục 13.
- **Vì sao CDC thay vì để service tự publish event**: không phải sửa code Product (không có "quên publish" — mọi thay
  đổi đã commit, kể cả UPDATE bằng SQL tay, đều đi ra). Snapshot ban đầu (`snapshot.mode=initial`) dựng index từ dữ
  liệu sẵn có mà không cần job import riêng.
- **Vì sao Kafka (không dùng lại RabbitMQ)**: Debezium chạy trên Kafka Connect; Kafka giữ thứ tự **theo key**
  (key = khóa chính → mọi thay đổi của một sản phẩm nằm cùng partition), lưu lại log để **replay** (dựng lại index =
  consumer group mới đọc từ đầu), và topic **compacted** giữ bản mới nhất của mỗi key nên replay không phình theo
  thời gian. RabbitMQ xóa message sau khi ack nên không replay được.

### Vì sao tồn kho KHÔNG nằm trong index

Bản đầu gộp `inventory_item` vào cùng document (lọc "còn hàng"). Đã bỏ, vì:

1. **Elasticsearch không cập nhật tại chỗ**: mỗi `update` = đọc document, chạy script, **index lại cả document**
   (phân tích lại `name`/`description`, kể cả edge n-gram), đánh dấu bản cũ đã xóa. Đổi một con số tồn kho tốn
   như index lại cả sản phẩm.
2. **Tồn kho đổi ở mỗi bước Saga**: reserve, commit, release → 2–3 lần index lại mỗi sản phẩm mỗi đơn. Flash sale
   10 000 đơn cho một sản phẩm ≈ 20 000 lần index lại **cùng một document**, tuần tự trên một partition (key = id) —
   lag tăng, và truy vấn tìm kiếm chậm đi đúng lúc đông người nhất.
3. **Con số trong index vốn đã sai**: trễ ~1–2 giây lúc bình thường, nhiều hơn khi tải cao. Checkout mới là nơi quyết
   định (Inventory giữ hàng bằng khóa), nên search không cần con số chính xác.
4. **Không lọc/sắp xếp theo tồn kho** (các sàn lớn cũng không có bộ lọc "còn hàng") → không còn lý do nào để index nó.

Thay vào đó UI tìm trước, rồi đọc **tồn kho thật** cho đúng các sản phẩm đang hiển thị (≤ 50) bằng
`GET /api/inventory/batch` — một request, số luôn đúng, flash sale không chạm tới Elasticsearch. Nếu sau này cần lọc
theo tồn kho, index **trạng thái thô** (`IN_STOCK`/`LOW_STOCK`/`OUT_OF_STOCK`) và chỉ ghi khi vượt ngưỡng
(`REPLICA IDENTITY FULL` để so trạng thái `before`/`after` trước khi gọi Elasticsearch), không index số lượng.

### Trade-off — cái giá phải trả

1. **Coupling vào schema**: tên cột (`price_vnd`, `updated_at`) trở thành hợp đồng với search-service. Đổi tên cột là
   breaking change. Cách giảm: Debezium **Outbox Event Router** (CDC trên bảng `outbox_event`, payload do service tự
   định nghĩa) — tách schema nội bộ khỏi hợp đồng, đổi lại phải sửa code service để ghi outbox. Ở đây chọn CDC bảng thô
   vì mục đích học CDC; hệ thống thật thường dùng outbox khi nhiều team phụ thuộc.
2. **Eventual consistency**: kết quả tìm kiếm trễ vài trăm ms tới vài giây. Giá trong search **chỉ để hiển thị** —
   checkout luôn đọc giá từ Product Service.
3. **Vận hành**: thêm Kafka, Kafka Connect, Elasticsearch (~2.5GB RAM) → để trong compose profile `search`.
   Replication slot giữ WAL của **cả server** tới khi Debezium xác nhận đã đọc: Debezium tắt lâu → WAL tích tụ. Chặn
   bằng `max_slot_wal_keep_size=1GB` (vượt trần thì slot bị vô hiệu → phải snapshot lại, nhưng các database khác không
   bị đầy đĩa). Gỡ connector **không** gỡ slot — phải `pg_drop_replication_slot` tay. Theo dõi:
   `SELECT slot_name, active, wal_status FROM pg_replication_slots;`.

### Đúng thứ tự và idempotent

- Consumer commit offset sau mỗi record (at-least-once). Xử lý lại an toàn vì script so `version` (cột `@Version` của
  JPA): bản có version **nhỏ hơn** bản đang có bị bỏ qua (`noop`) — bảo vệ khi replay đè lên dữ liệu mới hơn. Cho phép
  version **bằng**: UPDATE bằng SQL thô không tăng version, nhưng trong cùng partition bản đến sau luôn mới hơn.
- Xóa sản phẩm → đánh dấu `deleted=true` vĩnh viễn (UUID không dùng lại) nên bản cũ đến muộn không "hồi sinh" nó.
- Lỗi tạm thời (Elasticsearch down) → **retry mãi, chặn partition** thay vì đẩy sang DLT: bỏ qua một bản cập nhật rồi
  áp dụng bản sau sẽ làm read model sai. Chỉ message không đọc được mới vào `<topic>.DLT`. Đây là khác biệt cố ý với
  consumer RabbitMQ (mục 9), nơi các command độc lập với nhau nên retry queue + DLQ không làm sai thứ tự nghiệp vụ.

### Đổi mapping không downtime (blue/green)

Ghi vào index thật (`products-v2`), đọc qua alias `products`. Index mới = consumer group mới
(`search-indexer-<index>`) đọc topic từ đầu vào index mới trong khi alias vẫn trỏ index cũ; khi listener **idle** (đã
đọc hết topic) mới chuyển alias bằng một request `_aliases` nguyên tử. Hai điều kiện để cơ chế này đúng — cả hai đều
từng sai và chỉ lộ ra khi chạy với Kafka/Debezium thật:

- **Group phải thật sự mới.** `@KafkaListener(id = ...)` mặc định dùng `id` làm group id, ghi đè cấu hình → group cũ
  (đã có offset) không replay gì, "idle" đến ngay và alias chuyển sang **index rỗng**. Sửa: `idIsGroup = false`.
- **Topic phải do Debezium tạo.** Nếu consumer khởi động trước khi connector được đăng ký và được phép tự tạo topic,
  broker tạo topic với cấu hình mặc định (1 partition, `cleanup.policy=delete` — xóa sau 7 ngày); Debezium thấy topic
  đã có nên dùng luôn → mất compaction, replay sau 7 ngày thiếu các sản phẩm không đổi. Sửa:
  `allow.auto.create.topics=false` ở consumer.

### Bảo mật

User `cdc_user` chỉ có `REPLICATION`, `CONNECT` vào `product_db` và `SELECT` đúng bảng `catalog_document` (trước mục 13
là bảng `products`); publication chỉ chứa bảng đó (Debezium không thấy outbox hay bảng nào khác). Mật khẩu truyền qua `EnvVarConfigProvider`
(`${env:CDC_DB_PASSWORD}`), không nằm trong cấu hình connector lưu ở topic `_connect_configs`. Elasticsearch tắt
security **chỉ ở local**.

## 13. Catalog SPU/SKU và `catalog_document` — CDC trên một bảng "đã công bố" thay vì bảng thô

- **Mô hình**: `product` = **SPU** (thông tin chung: tên, slug, mô tả Markdown, thông số, ảnh, các trục biến thể
  `options` như Màu/Size); `product_sku` = **SKU** (một tổ hợp giá trị option, có mã, giá, giá gạch ngang, ảnh riêng,
  trạng thái). **Đơn vị bán là SKU**: giỏ hàng, tồn kho, giữ chỗ, dòng đơn hàng đều mang `skuId`.
- **Aggregate**: mọi thay đổi SKU/ảnh đi qua `Product` (aggregate root) để kiểm tra bất biến ở một chỗ — thuộc tính SKU
  khớp đúng options, không hai SKU trùng tổ hợp (unique index `(product_id, attributes)` trên JSONB là chốt chặn
  cuối), ảnh gắn cho SKU phải là ảnh của chính SPU. Mỗi thay đổi khóa dòng `product` (`SELECT ... FOR UPDATE`):
  các thao tác trên cùng sản phẩm chạy tuần tự.
- **Vì sao không CDC từng bảng như trước**: search cần MỘT document mỗi SPU với dữ liệu gộp (giá min/max qua các SKU
  bán được, ảnh đại diện, điểm đánh giá). Đổi giá một SKU không chạm dòng `product` → CDC bảng thô buộc search-service
  tự "join" nhiều topic, giữ trạng thái trung gian, xử lý thứ tự chéo topic — phức tạp và dễ sai.
- **Thiết kế**: product-service ghi bảng `catalog_document(product_id, version, schema_version, payload jsonb)` —
  một dòng mỗi SPU, ghi đè — **trong cùng transaction** với thay đổi catalog (`ProductWriter` → `CatalogProjection`).
  Debezium chỉ đọc bảng này (connector `webshop-catalog`, topic `cdc.catalog.public.catalog_document`).
  - Giống Outbox ở chỗ: ghi nguyên tử cùng nghiệp vụ, hợp đồng (payload) tách khỏi schema nội bộ — đổi tên cột
    `product_sku` không làm vỡ search (giải trade-off 1 của mục 12).
  - Khác Outbox ở chỗ: lưu **trạng thái** (ghi đè) chứ không phải chuỗi event. Snapshot ban đầu của Debezium đọc được
    toàn bộ catalog; mất dữ liệu Kafka (local không mount volume) thì snapshot lại là đủ; topic compacted giữ đúng
    bản mới nhất mỗi SPU. Outbox thuần (insert rồi xóa) thì snapshot không thấy gì.
  - `version` tăng bằng `UPSERT ... SET version = version + 1` dưới khóa dòng → search bỏ qua bản cũ đến muộn
    (cùng cơ chế "chống ghi lùi" của mục 12).
  - `schema_version` + `CatalogBackfill` (chạy lúc khởi động, trước readiness): SPU chưa có document (tạo bằng SQL
    migration) hoặc document theo schema cũ được dựng lại — đổi cấu trúc payload = tăng hằng số, không cần job import.
- **Chi phí**: mỗi thay đổi catalog ghi thêm một dòng JSON (vài KB); payload là hợp đồng phải giữ tương thích (thêm field
  thoải mái; đổi/bỏ field → tăng `schema_version` và dựng index mới `products-v4`).

### Chuyển dữ liệu cũ mà không chạm database của service khác

`products` cũ có id được Cart/Inventory/Order tham chiếu. Migration V4 biến mỗi dòng cũ thành 1 SPU + 1 **SKU mặc định
giữ nguyên id cũ**; SPU nhận id mới suy ra tất định (`md5('spu:' || id)`). Nhờ vậy dữ liệu ở service khác vẫn đúng:
cart/inventory/order chỉ cần **đổi tên cột** `product_id → sku_id` trong migration của chính chúng — không service nào
đọc/ghi database của service khác, không cần đồng bộ dữ liệu chéo. Dòng `order_item` cũ có `product_id` (SPU) = NULL:
snapshot đơn hàng bất biến (trigger cấm UPDATE) và Order Service không biết SPU của SKU cũ.

### Đổi hợp đồng message: version 2 + upcast v1

`ReserveInventory` và `OrderConfirmed` chuyển sang **v2** (dòng hàng theo `skuId`; `OrderConfirmed` thêm `productId`,
`productName`, `variantName` cho review-service). Consumer vẫn nhận **v1** và *upcast* (`productId` cũ chính là id SKU
mặc định) → message phát trước lúc nâng cấp (đang nằm trong queue, retry queue, DLQ chờ redrive) không bị đẩy vào DLQ
vì "version không hỗ trợ". Review-service bỏ qua `OrderConfirmed` v1 (không có SPU để ghi nhận lượt mua). Thứ tự triển
khai an toàn: consumer (cart, inventory, review) trước, producer (order) sau.

## 14. Ảnh sản phẩm: object storage S3 (SeaweedFS ở local), upload qua service, đọc thẳng từ storage

- **Lưu**: object storage tương thích S3 (SeaweedFS ở local; production đổi endpoint sang S3/R2/GCS, không đổi code). Ban đầu dùng MinIO, nhưng MinIO đã
  ngừng phát hành image cộng đồng — đổi sang SeaweedFS chỉ sửa docker-compose, đúng lợi ích của việc chỉ phụ thuộc S3 API. DB chỉ
  lưu `object_key`; URL công khai ghép lúc trả response từ `STORAGE_PUBLIC_BASE_URL` (chuyển sang CDN không phải sửa dữ liệu).
- **Ghi**: backoffice → BFF → gateway (route riêng `product-images`, giới hạn 6MB thay vì 1MB) → product-service.
  Service kiểm tra **nội dung file** (magic bytes JPEG/PNG/WebP), không tin `Content-Type`/đuôi file — bucket không bao
  giờ chứa HTML/SVG có script. Key mới cho mỗi lần upload (`products/{spu}/{imageId}.{ext}`) → cache vĩnh viễn
  (`Cache-Control: immutable`).
- **Đọc**: chỉ cho đọc object ẩn danh (không liệt kê, không ghi) — local cấu hình bằng identity `anonymous` của
  SeaweedFS (`infra/seaweedfs/start.sh`), S3 thật dùng bucket policy `s3:GetObject` (product-service tự gửi, best-effort); trình duyệt tải ảnh thẳng từ storage.
  Ảnh là dữ liệu công khai — proxy qua Java service chỉ tốn thread và băng thông.
- **Không có transaction chung giữa S3 và PostgreSQL** → chọn thứ tự để lỗi giữa chừng chỉ để lại **file mồ côi** (vô hại),
  không bao giờ có **dòng DB trỏ tới file không tồn tại**: upload file TRƯỚC rồi ghi DB (ghi DB lỗi → xóa file best-effort);
  xóa dòng DB TRƯỚC rồi mới xóa file. Lời gọi storage nằm ngoài transaction DB (không giữ khóa dòng SPU khi chờ mạng).
- **Vì sao không presigned URL** (trình duyệt PUT thẳng lên S3): bớt tải cho service, nhưng cần bước "xác nhận" để kiểm
  tra file sau khi đã nằm trên storage, cấu hình CORS bucket, và trong Docker host dùng để ký (`object-storage:8333`) khác host
  trình duyệt gọi (`localhost:8333`) nên chữ ký không khớp. Với ảnh ≤ 5MB của backoffice, upload qua service đơn giản
  và an toàn hơn.
- **Chưa có**: resize/thumbnail (CDN xử lý ảnh hoặc job bất đồng bộ), job dọn file mồ côi, credential hẹp hơn cho
  service (local cấp quyền Admin — production cấp user chỉ có `PutObject/DeleteObject` trên đúng bucket).

## 15. Review-service: chỉ người đã mua mới đánh giá — quyền dựng từ event, không gọi đồng bộ

- **Ai được viết**: review-service nghe `OrderConfirmed` v2 và ghi `purchase(user, product, order)`. Viết đánh giá chỉ
  kiểm tra bảng của chính nó — không có API "user X đã mua Y chưa?" ở Order Service, và review vẫn chạy khi Order down.
  Cái giá: eventual consistency — ngay sau khi đơn CONFIRMED có thể trễ vài trăm ms mới viết được (UI giải thích).
- **Mỗi người một đánh giá mỗi SPU**: `PUT /api/reviews/products/{id}/mine` (idempotent: gửi lại = ghi đè), unique
  `(product_id, user_id)`. Tên hiển thị che bớt từ claim `email` của JWT (`ng***@gmail.com`), không lưu email đầy đủ.
- **Tóm tắt điểm đúng khi ghi đồng thời**: mọi thay đổi review của một sản phẩm khóa dòng `product_rating` TRƯỚC, rồi
  **đếm lại** từ bảng review (không cộng/trừ dồn) dưới khóa. Ở READ COMMITTED, transaction thứ hai chờ khóa và khi đếm
  sẽ thấy review mà transaction thứ nhất đã commit → không có "lost update" trên tổng.
- **Đẩy điểm sang nơi hiển thị**: cùng transaction ghi Outbox `ProductRatingChanged{count, sum, version}` → Product Service
  lưu bản sao (`rating_count/rating_sum`) và ghi lại `catalog_document` → search có điểm để sắp xếp. Gửi **tổng** thay vì
  "+1 sao" để consumer chỉ cần giữ bản mới nhất; `version` để bỏ qua bản cũ đến muộn (retry queue có thể đảo thứ tự).
  Trang sản phẩm và search không phụ thuộc review-service còn sống.
- **Kiểm duyệt**: ADMIN ẩn/hiện qua `/api/reviews/moderation` (đi qua gateway như API nghiệp vụ, không phải `/admin/**`
  vận hành). Người viết sửa bài đã bị ẩn thì bài **vẫn ẩn** — không "lách" kiểm duyệt bằng cách sửa.

## 16. Tách storefront và backoffice thành hai ứng dụng

- **storefront-ui** (:3000, khách hàng): BFF chỉ chuyển tiếp `/api/**` tới gateway — không có đường tắt vào service.
  Chỉ có phiên khách hàng.
- **backoffice-ui** (:3001, nhân viên): mọi trang sau cổng đăng nhập ADMIN; nghiệp vụ quản trị (catalog, tồn kho, kiểm
  duyệt) đi qua gateway; trang Vận hành gọi thẳng `/admin/**`, `/actuator/**` của từng service qua BFF.
- **Vì sao tách hẳn** thay vì một app có trang `/admin`:
  1. **Bề mặt tấn công**: lỗ hổng XSS ở cửa hàng (nơi hiển thị dữ liệu người dùng nhập: đánh giá, tên...) không chạm
     được token ADMIN — hai origin khác nhau nên `localStorage` tách biệt — và BFF của cửa hàng không có đường tới
     endpoint vận hành.
  2. **Triển khai độc lập**: backoffice có thể nằm trong mạng nội bộ/sau VPN/SSO, có nhịp phát hành riêng.
  3. **Bundle nhỏ hơn** cho khách: không tải code của editor SKU, upload ảnh, DLQ.
- **Gateway vẫn chỉ xác thực, không phân quyền**: các đường quản trị nằm dưới prefix GET công khai
  (`/api/products/manage/**`, `/api/reviews/moderation/**`) được gateway yêu cầu token; quyền ADMIN do service kiểm tra.
- **Chi phí**: hai app trùng một ít code (API client, ErrorBox, Toast, ảnh). Có chủ đích chưa gom thành package dùng chung
  (npm workspaces) để mỗi app vẫn `npm ci` và build độc lập như các service Java.
