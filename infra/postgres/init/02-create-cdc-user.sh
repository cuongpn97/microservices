#!/bin/bash
# User cho Debezium (CDC). Chạy MỘT lần khi volume PostgreSQL còn trống, sau 01-create-databases.sh.
#
# Quyền tối thiểu:
#   - REPLICATION: mở replication slot để đọc WAL (logical decoding, plugin pgoutput).
#   - CONNECT chỉ vào product_db — không đọc được database khác.
#   - SELECT chỉ trên bảng được CDC (catalog_document): do Flyway của chính service đó GRANT
#     (bảng chưa tồn tại lúc script này chạy). Publication cũng do Flyway tạo vì chỉ OWNER của bảng mới tạo được.
#
# Volume đã có dữ liệu (script này không tự chạy lại) → chạy tay một lần, xem README mục "Search (CDC)".
set -euo pipefail

if [ -z "${CDC_DB_PASSWORD:-}" ]; then
  echo "CDC_DB_PASSWORD not set; skipping CDC user (search profile will not work)" >&2
  exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v pwd="$CDC_DB_PASSWORD" <<-'EOSQL'
	CREATE ROLE cdc_user WITH LOGIN REPLICATION PASSWORD :'pwd';
	GRANT CONNECT ON DATABASE product_db TO cdc_user;
EOSQL
echo "Created replication role cdc_user"
