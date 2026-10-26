# backoffice-ui — quản trị (Next.js 16)

Giao diện cho **nhân viên** (tài khoản role ADMIN — `ADMIN_EMAIL`/`ADMIN_PASSWORD` trong `../.env`):

- **Sản phẩm**: tạo/sửa SPU (tên, slug, thương hiệu, mô tả Markdown có xem trước, thông số, options như Màu/Size),
  bật/tắt bán; **SKU**: mã, thuộc tính, giá, giá gạch ngang, ảnh riêng, tạo hàng loạt mọi tổ hợp option còn thiếu;
  **tồn kho** từng SKU (Inventory Service); **ảnh**: upload nhiều file, sắp xếp (ảnh đầu là đại diện), alt text, xóa.
- **Đánh giá**: lọc theo trạng thái/sản phẩm, ẩn/hiện (điểm trung bình được tính lại và đẩy sang Product/Search).
- **Tổng quan / Vận hành**: sức khỏe từng service, outbox backlog, circuit breaker, DLQ (xem, redrive, gửi poison
  message), tra cứu Saga (`/operations?saga=<orderId>` — link từ trang đơn hàng của cửa hàng).

## Chạy

```bash
docker compose up -d --build          # ở thư mục gốc repo; backoffice ở http://localhost:3001
# hoặc dev:
cd backoffice-ui && cp .env.example .env.local && npm install && npm run dev   # http://localhost:3001
```

## Kiến trúc: BFF hai đường

```
Trình duyệt ──► Next.js ─┬─ /bff/gateway/api/**          ──► API Gateway :8080  (nghiệp vụ quản trị)
                         ├─ /bff/<service>/admin|actuator ──► service trực tiếp   (vận hành, gateway không route)
                         └─ /bff-tools/poison             ──► RabbitMQ Management API (sau khi xác minh token ADMIN)
```

- Nghiệp vụ quản trị (`/api/products/manage/**`, `PUT /api/inventory/{skuId}`, `/api/reviews/moderation/**`) đi qua
  gateway như mọi API khác: gateway đòi token, service kiểm tra role ADMIN.
- Upload ảnh là `multipart/form-data`; BFF chuyển tiếp body dạng nhị phân, gateway có route riêng giới hạn 6MB.
- `AdminGate` chỉ là trải nghiệm người dùng — quyền thật do service kiểm tra trên JWT.
- Origin riêng (cổng 3001) → token ADMIN không bao giờ nằm trong `localStorage` của cửa hàng.

## Cấu trúc

| Đường dẫn | Nội dung |
|---|---|
| `src/app/products/ProductList.tsx` | Danh sách SPU (tìm theo tên/slug, lọc trạng thái) |
| `src/app/products/DetailsForm.tsx` | Form SPU: thông tin, Markdown + xem trước, thông số, options |
| `src/app/products/[id]/SkuManager.tsx` | Bảng SKU + tồn kho, thêm SKU, sinh tổ hợp còn thiếu |
| `src/app/products/[id]/ImageManager.tsx` | Upload, sắp xếp, alt text, xóa ảnh |
| `src/app/reviews/ReviewModeration.tsx` | Kiểm duyệt đánh giá |
| `src/app/operations/OperationsView.tsx` | Health, breaker, Saga, DLQ |

Phiên bản: Next.js 16.4.2 (App Router, Cache Components bật), React 19, Tailwind CSS 4, react-markdown 10.
