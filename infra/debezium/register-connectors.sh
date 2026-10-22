#!/bin/sh
# Đăng ký (hoặc cập nhật) connector Debezium qua REST API của Kafka Connect.
# PUT /connectors/<name>/config là idempotent: chạy lại bao nhiêu lần cũng ra cùng cấu hình.
# Mật khẩu KHÔNG nằm trong JSON: "${env:CDC_DB_PASSWORD}" được worker thay bằng biến môi trường (EnvVarConfigProvider).
set -eu

CONNECT_URL="${CONNECT_URL:-http://kafka-connect:8083}"
DIR="$(dirname "$0")"

echo "Waiting for Kafka Connect at $CONNECT_URL ..."
until curl -sf "$CONNECT_URL/connectors" > /dev/null; do
  sleep 2
done

echo "Registering connector webshop-product"
curl -sf -X PUT -H "Content-Type: application/json" \
     --data @"$DIR/product-connector.json" \
     "$CONNECT_URL/connectors/webshop-product/config" > /dev/null

# Connector tồn kho đã bị bỏ (search không còn index tồn kho). Gỡ nếu còn từ bản cũ.
# Xóa connector KHÔNG xóa replication slot dbz_inventory: slot còn đó giữ WAL của cả PostgreSQL server (tới trần
# max_slot_wal_keep_size) — phải drop tay, xem README mục "Search (CDC)".
if curl -sf "$CONNECT_URL/connectors/webshop-inventory" > /dev/null; then
  echo "Removing obsolete connector webshop-inventory (remember to drop slot dbz_inventory)"
  curl -sf -X DELETE "$CONNECT_URL/connectors/webshop-inventory" > /dev/null
fi

curl -s "$CONNECT_URL/connectors?expand=status"
echo
