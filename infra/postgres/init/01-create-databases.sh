#!/bin/bash
# Chạy MỘT lần khi volume PostgreSQL còn trống (docker-entrypoint-initdb.d).
# Mỗi service có database + user riêng; user của service này KHÔNG có quyền CONNECT vào database của service khác.
# Schema/bảng do Flyway của từng service tạo — script này chỉ tạo "vỏ" database và user.
set -euo pipefail

create_service_db() {
  local db="$1" user="$2" password="$3"
  if [ -z "$password" ]; then
    echo "Missing password for $user" >&2
    exit 1
  fi
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v db="$db" -v usr="$user" -v pwd="$password" <<-'EOSQL'
	CREATE ROLE :"usr" WITH LOGIN PASSWORD :'pwd';
	CREATE DATABASE :"db" OWNER :"usr";
	REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
	GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO :"usr";
EOSQL
  echo "Created database $db owned by $user"
}

create_service_db auth_db      auth_user      "${AUTH_DB_PASSWORD:-}"
create_service_db product_db   product_user   "${PRODUCT_DB_PASSWORD:-}"
create_service_db cart_db      cart_user      "${CART_DB_PASSWORD:-}"
create_service_db inventory_db inventory_user "${INVENTORY_DB_PASSWORD:-}"
create_service_db payment_db   payment_user   "${PAYMENT_DB_PASSWORD:-}"
create_service_db order_db     order_user     "${ORDER_DB_PASSWORD:-}"
