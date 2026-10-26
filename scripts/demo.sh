#!/usr/bin/env bash
# Demo luồng đầy đủ qua API Gateway:
# đăng ký → nhận credit → đăng nhập → giỏ hàng → checkout → giữ hàng → giữ/capture credit → đơn CONFIRMED,
# cùng các kịch bản thất bại có bồi hoàn (thiếu credit, hết hàng) và kiểm tra bảo mật.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

KEYBOARD=11111111-1111-1111-1111-111111111111   # 1.500.000 VND
MOUSE=22222222-2222-2222-2222-222222222222      #   450.000 VND
MONITOR=33333333-3333-3333-3333-333333333333    # 6.900.000 VND, tồn kho demo = 2
LAPTOP=44444444-4444-4444-4444-444444444444     # 25.000.000 VND (> credit 10.000.000)

wait_for_gateway

step "1. Đăng ký user mới (nhận 10.000.000 VND credit qua UserRegistered → Payment)"
EMAIL="demo-$(date +%s)-$RANDOM@example.com"
TOKEN=$(register_and_login "$EMAIL" "demo-password-123")
[ -n "$TOKEN" ] || fail "no token"
ok "registered $EMAIL and logged in"

step "2. Chờ ví được khởi tạo (eventual consistency)"
WALLET=$(wait_wallet "$TOKEN")
echo "    $WALLET"
[ "$(echo "$WALLET" | jget availableVnd)" = "10000000" ] || fail "initial credit must be 10,000,000"
ok "available = 10,000,000 VND"

step "3. Danh sách sản phẩm (public)"
http GET /api/products >/dev/null; expect_status 200; ok "products listed"

step "4. Checkout khi chưa đăng nhập bị từ chối"
http POST /api/orders/checkout "" "" "Idempotency-Key: anon-$(uuid)" >/dev/null; expect_status 401; ok "401"

step "5. Thêm vào giỏ: 2 x Keyboard, 1 x Mouse"
http POST /api/cart/items "$TOKEN" "{\"skuId\":\"$KEYBOARD\",\"quantity\":2}" >/dev/null; expect_status 200
http POST /api/cart/items "$TOKEN" "{\"skuId\":\"$MOUSE\",\"quantity\":1}" >/dev/null; expect_status 200
CART=$(http GET /api/cart "$TOKEN"); expect_status 200
echo "    estimatedTotalVnd=$(echo "$CART" | jget estimatedTotalVnd)"
http POST /api/cart/items "$TOKEN" "{\"skuId\":\"$KEYBOARD\",\"quantity\":0}" >/dev/null; expect_status 400
ok "cart ready (quantity 0 rejected with 400)"

step "6. Checkout với Idempotency-Key (202 Accepted, Saga bắt đầu ở PENDING)"
KEY="checkout-$(uuid)"
RESP=$(http POST /api/orders/checkout "$TOKEN" "" "Idempotency-Key: $KEY"); expect_status 202
ORDER_ID=$(echo "$RESP" | jget orderId)
echo "    orderId=$ORDER_ID status=$(echo "$RESP" | jget status) total=$(echo "$RESP" | jget totalVnd)"

step "7. Client retry cùng Idempotency-Key → cùng đơn hàng, không tạo đơn mới"
RESP2=$(http POST /api/orders/checkout "$TOKEN" "" "Idempotency-Key: $KEY"); expect_status 200
[ "$(echo "$RESP2" | jget orderId)" = "$ORDER_ID" ] || fail "duplicate checkout created another order"
ok "same orderId returned"

step "8. Poll trạng thái đơn tới khi Saga kết thúc"
FINAL=$(wait_order "$TOKEN" "$ORDER_ID")
echo "    $FINAL"
[ "$(echo "$FINAL" | jget status)" = "CONFIRMED" ] || fail "order should be CONFIRMED"
ok "order CONFIRMED (inventory committed + credit captured)"

step "9. Ví sau khi capture: 10.000.000 - 3.450.000 = 6.550.000"
WALLET=$(http GET /api/wallet/me "$TOKEN"); echo "    $WALLET"
[ "$(echo "$WALLET" | jget balanceVnd)" = "6550000" ] || fail "balance should be 6,550,000"
[ "$(echo "$WALLET" | jget reservedVnd)" = "0" ] || fail "reserved should be 0"
ok "balance=6,550,000 reserved=0"

step "10. Giỏ hàng được dọn sau OrderConfirmed (bất đồng bộ)"
for _ in $(seq 1 20); do
  CART=$(http GET "/api/cart?pricing=false" "$TOKEN")
  if echo "$CART" | grep -q '"items":\[\]'; then break; fi; sleep 1
done
echo "$CART" | grep -q '"items":\[\]' || fail "cart was not cleared"
ok "cart cleared"

step "11. Thất bại có bồi hoàn: Laptop 25.000.000 > credit còn lại → giữ hàng rồi RELEASE"
STOCK_BEFORE=$(http GET /api/inventory/$LAPTOP | jget reservedQuantity)
http POST /api/cart/items "$TOKEN" "{\"skuId\":\"$LAPTOP\",\"quantity\":1}" >/dev/null; expect_status 200
RESP=$(http POST /api/orders/checkout "$TOKEN" "" "Idempotency-Key: laptop-$(uuid)"); expect_status 202
FINAL=$(wait_order "$TOKEN" "$(echo "$RESP" | jget orderId)")
echo "    $FINAL"
[ "$(echo "$FINAL" | jget status)" = "FAILED" ] || fail "laptop order should FAIL"
echo "$FINAL" | grep -q "CREDIT_REJECTED:INSUFFICIENT_FUNDS" || fail "unexpected failure reason"
[ "$(http GET /api/inventory/$LAPTOP | jget reservedQuantity)" = "$STOCK_BEFORE" ] || fail "inventory not released"
[ "$(http GET /api/wallet/me "$TOKEN" | jget balanceVnd)" = "6550000" ] || fail "balance changed by failed order"
ok "order FAILED, inventory reservation released, wallet untouched"
http DELETE /api/cart "$TOKEN" >/dev/null

step "12. Thất bại không cần bồi hoàn: Monitor chỉ còn 2 → đặt 3 bị từ chối ở bước giữ hàng"
http POST /api/cart/items "$TOKEN" "{\"skuId\":\"$MONITOR\",\"quantity\":3}" >/dev/null; expect_status 200
RESP=$(http POST /api/orders/checkout "$TOKEN" "" "Idempotency-Key: monitor-$(uuid)"); expect_status 202
FINAL=$(wait_order "$TOKEN" "$(echo "$RESP" | jget orderId)")
echo "    $FINAL"
echo "$FINAL" | grep -q "INVENTORY_REJECTED:INSUFFICIENT_STOCK" || fail "expected out-of-stock failure"
ok "order FAILED with INSUFFICIENT_STOCK, no credit touched"

printf '\n\033[1;32mDEMO PASSED\033[0m — orderId CONFIRMED: %s\n' "$ORDER_ID"
