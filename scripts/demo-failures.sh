#!/usr/bin/env bash
# Demo các kịch bản lỗi trên môi trường docker compose đang chạy:
#   A. Payment Service tạm dừng giữa Saga → command nằm chờ trong queue durable → service lên lại → đơn CONFIRMED
#   B. Poison message → retry có giới hạn → DLQ → xem qua endpoint admin
#   C. Product Service dừng → circuit breaker của Cart OPEN → fallback trả giỏ không có giá
set -euo pipefail
. "$(dirname "$0")/lib.sh"

KEYBOARD=11111111-1111-1111-1111-111111111111
COMPOSE="${COMPOSE:-docker compose}"

wait_for_gateway
ADMIN_TOKEN=$(login "${ADMIN_EMAIL:?set ADMIN_EMAIL in .env}" "${ADMIN_PASSWORD:?set ADMIN_PASSWORD in .env}")
[ -n "$ADMIN_TOKEN" ] || fail "admin login failed"

EMAIL="failure-$(date +%s)-$RANDOM@example.com"
TOKEN=$(register_and_login "$EMAIL" "demo-password-123")
wait_wallet "$TOKEN" >/dev/null

# ---------------------------------------------------------------------------------------------
step "A. Dừng payment-service rồi checkout — Saga phải CHỜ, không mất message, không fail sai"
$COMPOSE stop payment-service >/dev/null
http POST /api/cart/items "$TOKEN" "{\"skuId\":\"$KEYBOARD\",\"quantity\":1}" >/dev/null; expect_status 200
RESP=$(http POST /api/orders/checkout "$TOKEN" "" "Idempotency-Key: outage-$(uuid)"); expect_status 202
ORDER_ID=$(echo "$RESP" | jget orderId)
sleep 8
STATE=$(http GET "/api/orders/$ORDER_ID" "$TOKEN" | jget sagaState)
echo "    sau 8s khi payment down: sagaState=$STATE"
[ "$STATE" = "INVENTORY_RESERVED" ] || fail "expected saga to wait in INVENTORY_RESERVED"
ok "Saga đang chờ ở INVENTORY_RESERVED (ReserveCredit nằm trong queue durable payment.commands.q)"
$COMPOSE start payment-service >/dev/null
FINAL=$(wait_order "$TOKEN" "$ORDER_ID")
[ "$(echo "$FINAL" | jget status)" = "CONFIRMED" ] || fail "order should recover to CONFIRMED"
ok "payment-service lên lại → xử lý command tồn đọng → đơn CONFIRMED"
WALLET=$(http GET /api/wallet/me "$TOKEN")
[ "$(echo "$WALLET" | jget balanceVnd)" = "8500000" ] || fail "credit must be captured exactly once"
ok "chỉ trừ tiền một lần dù ReserveCredit có thể đã được gửi lại (balance=8,500,000)"

# ---------------------------------------------------------------------------------------------
step "B. Poison message → DLQ (không requeue vô hạn)"
BEFORE=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8085/admin/dlq)
curl -s -u "$RABBITMQ_USERNAME:$RABBITMQ_PASSWORD" -H "content-type: application/json" \
  -X POST "$RABBIT_MGMT/api/exchanges/%2F/webshop.events/publish" \
  -d '{"properties":{"delivery_mode":2,"message_id":"poison-demo"},"routing_key":"payment.command.reserve","payload":"this is not json","payload_encoding":"string"}' \
  >/dev/null
sleep 3
AFTER=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8085/admin/dlq)
echo "    DLQ trước: $BEFORE"
echo "    DLQ sau  : $AFTER"
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8085/admin/dlq/payment.commands.q.dlq/messages?max=1" | head -c 600; echo
ok "message hỏng nằm trong payment.commands.q.dlq kèm failureReason/lastError"

# ---------------------------------------------------------------------------------------------
step "C. Dừng product-service → circuit breaker 'product-service' của cart-service mở → fallback"
# Giỏ phải có hàng thì Cart mới cần gọi Product Service để lấy giá.
http POST /api/cart/items "$TOKEN" "{\"skuId\":\"$KEYBOARD\",\"quantity\":1}" >/dev/null; expect_status 200
$COMPOSE stop product-service >/dev/null
for i in $(seq 1 6); do
  http GET /api/cart "$TOKEN" >/dev/null || true
done
CART=$(http GET /api/cart "$TOKEN"); expect_status 200
PRICING=$(echo "$CART" | jget pricingAvailable | tr '[:upper:]' '[:lower:]')
echo "    GET /api/cart → 200, pricingAvailable=$PRICING"
[ "$PRICING" = "false" ] || fail "cart should degrade to pricingAvailable=false"
BREAKER=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8083/actuator/circuitbreakers)
echo "    $(echo "$BREAKER" | grep -o '"product-service":{[^}]*}' | head -c 400)"
echo "$BREAKER" | grep -q '"state":"OPEN"' || fail "circuit breaker should be OPEN"
# Khi OPEN: thêm sản phẩm (cần xác thực sản phẩm) → 503 ngay lập tức, không chờ timeout.
http POST /api/cart/items "$TOKEN" "{\"skuId\":\"$KEYBOARD\",\"quantity\":1}" >/dev/null; expect_status 503
$COMPOSE start product-service >/dev/null
ok "giỏ vẫn đọc được (không có giá), circuit OPEN → fail-fast 503 cho thao tác cần Product Service"
http DELETE /api/cart "$TOKEN" >/dev/null

printf '\n\033[1;32mFAILURE DEMO FINISHED\033[0m\n'
