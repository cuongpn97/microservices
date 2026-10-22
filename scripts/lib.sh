#!/usr/bin/env bash
# Hàm tiện ích dùng chung cho các script demo. Yêu cầu: bash, curl, và jq HOẶC python3.
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
RABBIT_MGMT="${RABBIT_MGMT:-http://localhost:15672}"

if [ -f "$(dirname "$0")/../.env" ]; then
  # shellcheck disable=SC1091
  set -a; . "$(dirname "$0")/../.env"; set +a
fi

PY=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import json" >/dev/null 2>&1; then
    PY="$candidate"; break
  fi
done

# jget <key>: đọc một trường cấp cao nhất từ JSON trên stdin.
jget() {
  if command -v jq >/dev/null 2>&1; then
    jq -r --arg k "$1" '.[$k] // empty'
  elif [ -n "$PY" ]; then
    "$PY" -c 'import sys,json
d=json.load(sys.stdin); v=d.get(sys.argv[1]) if isinstance(d,dict) else None
print("" if v is None else v)' "$1"
  else
    echo "Need jq or python3" >&2; exit 2
  fi
}

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[0;32m    OK: %s\033[0m\n' "$*"; }
fail() { printf '\033[0;31m    FAIL: %s\033[0m\n' "$*"; exit 1; }

STATUS_FILE="$(mktemp)"
trap 'rm -f "$STATUS_FILE"' EXIT

# http <method> <path> [token] [body] [extra-header] -> in body ra stdout, ghi HTTP status vào $STATUS_FILE
# (dùng file thay vì biến vì hàm thường được gọi trong $(...) — subshell không trả biến về được).
http() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}" extra="${5:-}"
  local args=(-s -o /tmp/webshop_body.$$ -w '%{http_code}' -X "$method" "$GATEWAY$path"
              -H "Content-Type: application/json")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$extra" ] && args+=(-H "$extra")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}" > "$STATUS_FILE"
  cat /tmp/webshop_body.$$
  rm -f /tmp/webshop_body.$$
}

last_status() { cat "$STATUS_FILE"; }

expect_status() {
  HTTP_STATUS=$(last_status)
  [ "$HTTP_STATUS" = "$1" ] || fail "expected HTTP $1 but got $HTTP_STATUS"
}

wait_for_gateway() {
  step "Chờ gateway và các service sẵn sàng"
  for _ in $(seq 1 90); do
    if curl -sf "$GATEWAY/actuator/health" >/dev/null 2>&1 \
       && curl -sf "$GATEWAY/api/products" >/dev/null 2>&1; then
      ok "gateway is up"; return 0
    fi
    sleep 2
  done
  fail "gateway not ready after 180s"
}

register_and_login() {
  local email="$1" password="$2"
  http POST /api/auth/register "" "{\"email\":\"$email\",\"password\":\"$password\",\"fullName\":\"Demo User\"}" >/dev/null
  expect_status 201
  http POST /api/auth/login "" "{\"email\":\"$email\",\"password\":\"$password\"}" | jget accessToken
}

login() {
  http POST /api/auth/login "" "{\"email\":\"$1\",\"password\":\"$2\"}" | jget accessToken
}

# wait_wallet <token>: chờ ví được tạo (eventual consistency sau đăng ký)
wait_wallet() {
  for _ in $(seq 1 30); do
    local body; body=$(http GET /api/wallet/me "$1")
    if [ "$(last_status)" = "200" ]; then echo "$body"; return 0; fi
    sleep 1
  done
  fail "wallet not initialized after 30s"
}

# wait_order <token> <orderId>: poll tới khi CONFIRMED hoặc FAILED
wait_order() {
  for _ in $(seq 1 60); do
    local body status; body=$(http GET "/api/orders/$2" "$1"); status=$(echo "$body" | jget status)
    if [ "$status" = "CONFIRMED" ] || [ "$status" = "FAILED" ]; then echo "$body"; return 0; fi
    sleep 1
  done
  fail "order $2 still PENDING after 60s"
}

uuid() {
  if [ -n "$PY" ]; then "$PY" -c 'import uuid; print(uuid.uuid4())'; else cat /proc/sys/kernel/random/uuid; fi
}
