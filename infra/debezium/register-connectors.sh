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

# CDC bảng catalog_document (một document JSON mỗi SPU) — xem docs/design-decisions.md mục 13.
echo "Registering connector webshop-catalog"
curl -sf -X PUT -H "Content-Type: application/json" \
     --data @"$DIR/catalog-connector.json" \
     "$CONNECT_URL/connectors/webshop-catalog/config" > /dev/null

# Connector cũ đã bị thay thế. Gỡ nếu còn từ bản trước:
#   webshop-inventory: search không còn index tồn kho.
#   webshop-product:   CDC bảng products thô, thay bằng webshop-catalog khi tách SPU/SKU.
# Xóa connector KHÔNG xóa replication slot (dbz_inventory, dbz_product): slot còn đó giữ WAL của cả PostgreSQL server
# (tới trần max_slot_wal_keep_size) — phải drop tay, xem README mục "Search (CDC)".
for obsolete in webshop-inventory webshop-product; do
  if curl -sf "$CONNECT_URL/connectors/$obsolete" > /dev/null; then
    echo "Removing obsolete connector $obsolete (remember to drop its replication slot)"
    curl -sf -X DELETE "$CONNECT_URL/connectors/$obsolete" > /dev/null
  fi
done

curl -s "$CONNECT_URL/connectors?expand=status"
echo
