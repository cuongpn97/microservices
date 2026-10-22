# Kịch bản lỗi, cửa sổ crash và cách phục hồi

Quy ước: "TX" = một transaction cục bộ trong database của một service. Không có transaction nào trải qua hai service.
Cột **Test** chỉ ra test tự động chứng minh hành vi (nếu có).

## 1. Đăng ký & cấp credit

| # | Sự cố | Trạng thái sau sự cố | Phục hồi tự động | Can thiệp thủ công | Test |
|---|---|---|---|---|---|
| 1.1 | Auth crash trước COMMIT | Không có user, không có event | Client đăng ký lại | — | `AuthIT` (rollback ⇒ không có outbox) |
| 1.2 | Auth crash sau COMMIT, trước khi publish | User + outbox chưa publish | Publisher gửi khi Auth lên lại | — | `OutboxPublisherIT.failedPublishIsRecorded...` |
| 1.3 | RabbitMQ down | Outbox tích lũy, `webshop.outbox.backlog` tăng | Publisher thử lại với backoff tới khi broker lên | Theo dõi backlog | `OutboxPublisherIT` |
| 1.4 | Broker ack nhưng Auth crash trước khi ghi `published_at` | Event sẽ được gửi lần 2 | Payment Inbox + `UNIQUE(wallet.user_id)` + `UNIQUE(ledger.idempotency_key)` ⇒ credit chỉ cấp 1 lần | — | `crashAfterBrokerConfirm...`, `PaymentMessagingIT.duplicateUserRegistered...`, `WalletIT.initialCreditIsGrantedExactlyOnce...` |
| 1.5 | Payment xử lý xong, crash trước ack | Message được giao lại | Inbox thấy đã xử lý ⇒ ack, không làm gì | — | `ReliableConsumerIT.duplicateDelivery...` |
| 1.6 | Payment DB lỗi tạm thời | Message đi retry queue (1s, 3s, 9s) | Thành công khi DB lên lại | Nếu vào DLQ: redrive | `ReliableConsumerIT.transientFailure...` |

**Trạng thái tạm thời được chấp nhận**: user đã tồn tại nhưng ví chưa có (thường < 1 giây).
- `GET /api/wallet/me` → `404 WALLET_NOT_INITIALIZED` (client thử lại sau).
- Checkout trong khoảng này: Payment trả `CreditReservationRejected(WALLET_NOT_FOUND)` → Saga bồi hoàn hàng → đơn `FAILED`. User checkout lại sau (giỏ hàng không bị xóa).
- Nếu message `UserRegistered` rơi vào DLQ (ví dụ payment DB hỏng kéo dài), ví sẽ không được tạo cho tới khi operator redrive — xem mục 5.

## 2. Checkout (bước đồng bộ)

| # | Sự cố | Kết quả | Ghi chú | Test |
|---|---|---|---|---|
| 2.1 | Client timeout, gửi lại cùng `Idempotency-Key` | Trả lại đơn cũ (200, header `Idempotent-Replayed: true`) | Unique `(user_id, idempotency_key)` | `sameIdempotencyKeyReturnsSameOrderEvenUnderConcurrency` |
| 2.2 | Hai request song song cùng key | Một đơn duy nhất; request thua bắt `DataIntegrityViolation` rồi đọc đơn thắng | | như trên |
| 2.3 | Hai request khác key, cùng phiên bản giỏ | Request thứ hai: `409 CHECKOUT_ALREADY_IN_PROGRESS` | Partial unique index `(user_id, cart_revision) WHERE status <> 'FAILED'` | `differentKeysForSameCartRevision...` |
| 2.4 | Cart/Product Service down hoặc chậm | `503 DEPENDENCY_UNAVAILABLE` sau ≤ 3 lần thử GET; khi breaker OPEN thì fail-fast | Chưa có gì được ghi ⇒ an toàn để thử lại | `ResilientCallerTest`, `inactiveProductOrEmptyCart...` |
| 2.5 | Order crash giữa lúc gọi HTTP và COMMIT | Không có đơn | Client retry cùng key ⇒ tạo đơn bình thường | — |
| 2.6 | Order crash sau COMMIT, trước khi trả response | Đơn PENDING tồn tại, client không biết orderId | Client retry cùng key ⇒ nhận lại đúng đơn đó | 2.1 |

## 3. Saga (bước bất đồng bộ)

| # | Sự cố | Trạng thái | Phục hồi | Test |
|---|---|---|---|---|
| 3.1 | Inventory down khi đang `PENDING` | Command nằm trong `inventory.commands.q` (durable) | Inventory lên lại ⇒ xử lý. Nếu quá `step-timeout` (15s) ⇒ watcher gửi lại `ReserveInventory` (cùng reservationId), backoff 15s/30s/60s; hết 3 lần ⇒ `COMPENSATING` + `ReleaseInventory` | `CheckoutSagaTest.reserveTimeout...`, `reserveTimeoutsExhaustedCompensate...` |
| 3.2 | Inventory đã giữ hàng nhưng reply bị mất/chậm (timeout sau side effect) | Saga `PENDING`, hàng đang bị giữ | Gửi lại `ReserveInventory` ⇒ Inventory thấy `reservationId` đã có ⇒ trả `InventoryReserved` lần nữa, **không giữ thêm** | `InventoryReservationIT.duplicateReserveCommands...`, `InventoryMessagingIT` |
| 3.3 | Release đến trước Reserve (Saga đã bỏ cuộc) | Inventory/Payment tạo tombstone `RELEASED` | Reserve đến muộn ⇒ bị từ chối, không giữ tài nguyên mồ côi | `releaseBeforeReserveLeavesTombstone...` (cả hai service) |
| 3.4 | Payment từ chối (thiếu credit, chưa có ví) | `COMPENSATING` | `ReleaseInventory` ⇒ `InventoryReleased` ⇒ `FAILED` | `creditRejectionReleasesInventoryAndFailsOrder` |
| 3.5 | Payment down khi `INVENTORY_RESERVED` | Command chờ trong queue; hàng đang bị giữ | Payment lên trong ~105s (15+30+60) ⇒ tiếp tục bình thường. Quá thời gian ⇒ `ReleaseCredit` + `ReleaseInventory` ⇒ `FAILED` | `scripts/demo-failures.sh` (A), `paymentTimeoutReleasesBoth...` |
| 3.6 | **Capture thành công nhưng Order crash trước khi ghi nhận** | Payment: CAPTURED, ledger có CAPTURE. Order: `CONFIRMING`, payment step `CONFIRM_PENDING` | (a) Reply `CreditCaptured` vẫn nằm trong `order.saga-replies.q` chưa ack ⇒ Order lên lại xử lý. (b) Nếu reply mất hẳn ⇒ watcher gửi lại `CaptureCredit` ⇒ Payment thấy đã CAPTURED ⇒ trả `CreditCaptured` lần nữa, **không trừ tiền lần 2** ⇒ đơn `CONFIRMED` | `captureSucceededButReplyLost_...` (order), `captureSucceedsButReplyIsLost_resendIsSafeAndChargesOnce` (payment) |
| 3.7 | Order crash giữa Saga (bất kỳ bước nào) | Toàn bộ state trong `checkout_saga` | Reply chưa ack được giao lại; watcher tiếp tục theo `deadline_at` | `sagaResumesFromPersistedStateWhenRepliesAreMissing` |
| 3.8 | Reply trùng (redelivery) hoặc reply cho command gửi lại | Inbox chặn bản trùng `eventId`; reply mới cùng ý nghĩa bị máy trạng thái bỏ qua vì state không khớp | — | `duplicateReplyMessagesDoNotAdvanceSagaTwice`, `CheckoutSagaTest.duplicateAndLateReplies...` |
| 3.9 | Commit/Capture bị **từ chối** (không được xảy ra) | Saga giữ `CONFIRMING`, `needs_attention = true`, metric `webshop.saga.needs-attention` | Không tự đoán | Xem mục 4 | `unexpectedRejectionDuringConfirmation...` |
| 3.10 | Compensation lỗi kéo dài (participant down nhiều giờ) | `COMPENSATING`, retry mãi với backoff trần 2 phút; sau 5 lần bật `needs_attention` | Tự xong khi participant lên lại | Theo dõi `webshop.saga.needing-attention` | `compensationKeepsRetrying...` |
| 3.11 | Order confirm xong, `OrderConfirmed` tới Cart trễ | Giỏ còn hàng cũ một lúc | Cart trừ đúng số lượng đã mua khi nhận event; checkout lại cùng revision bị 409 nên không mua trùng | `orderConfirmedRemovesOnlyPurchasedQuantities...` |

### Vì sao không có trạng thái "đã trừ tiền nhưng đơn thất bại"

Capture chỉ được gửi khi Saga đã vào `CONFIRMING`, và từ `CONFIRMING` Saga không bao giờ chuyển sang bồi hoàn. Release
credit chỉ được gửi từ `INVENTORY_RESERVED`/`PENDING` (trước điểm không quay đầu). Payment từ chối release một reservation
đã CAPTURED (`CreditReleaseRejected: ALREADY_CAPTURED`). Do đó, nếu bất biến được giữ, tiền đã capture luôn tương ứng một
đơn `CONFIRMED` (hoặc đang `CONFIRMING` và sẽ thành `CONFIRMED`).

## 4. Đối soát (reconciliation) và can thiệp thủ công

Các endpoint `/admin/**` cần token ADMIN và chỉ gọi trực tiếp port service (gateway không route):

```bash
ADMIN=$(curl -s localhost:8080/api/auth/login -H 'content-type: application/json' \
  -d '{"email":"admin@webshop.local","password":"local-only-admin-pw"}' | jq -r .accessToken)

# Saga cần chú ý
curl -s -H "Authorization: Bearer $ADMIN" localhost:8086/admin/sagas/attention
# Chi tiết + lịch sử chuyển trạng thái
curl -s -H "Authorization: Bearer $ADMIN" localhost:8086/admin/sagas/<orderId>
# Ép xử lý ngay ở lần quét tới (sau khi đã sửa sự cố downstream)
curl -s -X POST -H "Authorization: Bearer $ADMIN" localhost:8086/admin/sagas/<orderId>/retry-now
# Đối chiếu phía participant
curl -s -H "Authorization: Bearer $ADMIN" localhost:8084/admin/reservations/<orderId>
curl -s -H "Authorization: Bearer $ADMIN" localhost:8085/admin/credit-reservations/<orderId>
```

Quy trình đối soát đề xuất (chưa tự động hóa — xem Known gaps):

1. Lấy các Saga `needs_attention = true` hoặc non-terminal quá N phút.
2. Với mỗi Saga, so sánh `inventory_step`/`payment_step` với trạng thái thật ở `/admin/reservations/{id}` và `/admin/credit-reservations/{id}`.
3. Nếu participant đã ở trạng thái đích (COMMITTED/CAPTURED/RELEASED) ⇒ `retry-now`: command gửi lại sẽ nhận reply idempotent và Saga tự hoàn tất.
4. Nếu mâu thuẫn thật (ví dụ inventory COMMITTED nhưng credit RELEASED do can thiệp tay sai) ⇒ cần nghiệp vụ bù trừ (hoàn kho/hoàn tiền) — ngoài phạm vi; ghi nhận thủ công.

Bất biến có thể kiểm tra định kỳ bằng SQL trong từng database:

```sql
-- payment_db: tổng ledger khớp số dư ví
SELECT w.user_id, w.balance_vnd,
       SUM(CASE l.entry_type WHEN 'INITIAL_CREDIT' THEN l.amount_vnd WHEN 'CAPTURE' THEN -l.amount_vnd ELSE 0 END) AS ledger_balance
FROM wallet w JOIN wallet_ledger l ON l.wallet_id = w.id GROUP BY w.id HAVING w.balance_vnd <>
     SUM(CASE l.entry_type WHEN 'INITIAL_CREDIT' THEN l.amount_vnd WHEN 'CAPTURE' THEN -l.amount_vnd ELSE 0 END);

-- inventory_db: reserved_quantity bằng tổng các reservation đang RESERVED
SELECT i.product_id, i.reserved_quantity, COALESCE(h.held, 0) AS held
FROM inventory_item i
LEFT JOIN (SELECT l.product_id, SUM(l.quantity) AS held
           FROM reservation_line l JOIN reservation r ON r.id = l.reservation_id
           WHERE r.status = 'RESERVED' GROUP BY l.product_id) h ON h.product_id = i.product_id
WHERE i.reserved_quantity <> COALESCE(h.held, 0);
```

## 5. DLQ: kiểm tra, redrive, replay

Mỗi service sở hữu DLQ của queue mà nó consume:

| Service | Port | DLQ |
|---|---|---|
| payment-service | 8085 | `payment.user-registered.q.dlq`, `payment.commands.q.dlq` |
| inventory-service | 8084 | `inventory.commands.q.dlq` |
| order-service | 8086 | `order.saga-replies.q.dlq` |
| cart-service | 8083 | `cart.order-events.q.dlq` |

```bash
# Số message trong các DLQ của service
curl -s -H "Authorization: Bearer $ADMIN" localhost:8085/admin/dlq
# Xem (không lấy ra) tối đa 5 message: messageId, eventType, originalRoutingKey, attempt, failureReason, lastError, body
curl -s -H "Authorization: Bearer $ADMIN" "localhost:8085/admin/dlq/payment.commands.q.dlq/messages?max=5"
# Sau khi sửa nguyên nhân: đưa tối đa 10 message về queue gốc
curl -s -X POST -H "Authorization: Bearer $ADMIN" "localhost:8085/admin/dlq/payment.commands.q.dlq/redrive?max=10"
```

- Redrive publish lại **vào đúng queue gốc** (qua default exchange), không vào topic exchange — tránh giao trùng cho consumer khác.
- `messageId`/body giữ nguyên ⇒ Inbox vẫn chặn nếu message thực ra đã được xử lý. `x-attempt` reset về 1, `x-redrive-count` tăng.
- Redrive dùng publisher confirm rồi mới ack bản trong DLQ ⇒ không mất message khi lỗi giữa chừng.
- Message không parse được (poison) sẽ quay lại DLQ ngay — xóa bằng RabbitMQ UI (Queues → `...dlq` → Purge) sau khi đã lưu lại nội dung để điều tra.
- Có thể dùng RabbitMQ Management UI (http://localhost:15672 → Queues → Get messages / Move messages) — lưu ý "Move messages" của UI dùng shovel và KHÔNG reset `x-attempt`.

## 6. Hạ tầng

| Sự cố | Hành vi |
|---|---|
| PostgreSQL của một service down | Service đó trả 5xx cho HTTP; consumer của nó đẩy message vào retry ⇒ DLQ nếu kéo dài (redrive sau). Outbox poller log cảnh báo và thử lại. Các service khác vẫn chạy. |
| RabbitMQ down | HTTP vẫn chạy; outbox tích lũy; Saga tạm dừng (timeout watcher gửi lại vào outbox — cũng tích lũy). Khi broker lên: publisher xả backlog, mọi thứ tiếp tục. Message durable + persistent không mất khi broker restart. |
| Mất message do broker mất dữ liệu đĩa | Không tự phục hồi hoàn toàn: outbox đã đánh dấu published. Saga watcher sẽ gửi lại command (nên Saga vẫn kết thúc); `UserRegistered` mất thì cần replay thủ công từ `outbox_event` của Auth (đặt lại `published_at = NULL` cho dòng liên quan). |
