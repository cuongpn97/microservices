#!/bin/sh
# Khởi động SeaweedFS (master + volume + filer + S3 gateway trong một tiến trình) cho môi trường LOCAL.
# Sinh file định danh S3 từ biến môi trường để secret nằm trong .env, không nằm trong repo:
#   - product-service: access key đầy đủ quyền (ghi/xóa ảnh, tạo bucket).
#   - anonymous: CHỈ đọc object trong bucket ảnh — trình duyệt tải ảnh trực tiếp, không liệt kê, không ghi được.
set -eu
: "${S3_ACCESS_KEY:?S3_ACCESS_KEY is required}"
: "${S3_SECRET_KEY:?S3_SECRET_KEY is required}"
BUCKET="${S3_BUCKET:-product-images}"

cat > /tmp/s3.json <<JSON
{
  "identities": [
    {
      "name": "product-service",
      "credentials": [{ "accessKey": "${S3_ACCESS_KEY}", "secretKey": "${S3_SECRET_KEY}" }],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    },
    { "name": "anonymous", "actions": ["Read:${BUCKET}"] }
  ]
}
JSON

exec weed server -dir=/data -s3 -s3.port=8333 -s3.config=/tmp/s3.json
