# Quyết định thiết kế

Mỗi mục trả lời: **vì sao dùng**, **giải quyết vấn đề gì**, **KHÔNG giải quyết gì**, **chi phí vận hành**.

---

## 1. Database-per-service (một PostgreSQL server, sáu database)

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
  (logical decoding, `pgoutput`) cho đúng bảng `products` → Kafka → search-service upsert vào index.
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

User `cdc_user` chỉ có `REPLICATION`, `CONNECT` vào `product_db` và `SELECT` đúng bảng `products`; publication chỉ
chứa bảng đó (Debezium không thấy outbox hay bảng nào khác). Mật khẩu truyền qua `EnvVarConfigProvider`
(`${env:CDC_DB_PASSWORD}`), không nằm trong cấu hình connector lưu ở topic `_connect_configs`. Elasticsearch tắt
security **chỉ ở local**.
