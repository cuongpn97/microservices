# storefront-ui — cửa hàng (Next.js 16)

Giao diện cho **khách hàng**: danh mục sản phẩm (SPU), trang chi tiết chọn biến thể (SKU) theo màu/size…, tồn kho từng
biến thể, mô tả Markdown, thông số, đánh giá của người đã mua; giỏ hàng, checkout với Idempotency-Key, **theo dõi Saga
trực tiếp**, ví + sổ cái. Trang *Kịch bản demo* (`/demo`, link ở footer) chạy tự động các tình huống thành công/thất bại.

Phần quản trị nằm ở ứng dụng riêng: [`../backoffice-ui`](../backoffice-ui/README.md) (lý do tách:
[design-decisions §16](../docs/design-decisions.md)).

## Chạy

Cách 1 — cùng Docker Compose (khuyến nghị):

```bash
docker compose up -d --build          # ở thư mục gốc repo; cửa hàng ở http://localhost:3000
```

Cách 2 — chạy dev ngoài Docker (backend vẫn chạy bằng compose):

```bash
cd storefront-ui
cp .env.example .env.local
npm install
npm run dev                           # http://localhost:3000
```

## Kiến trúc: BFF chỉ tới gateway

```
Trình duyệt ──(cùng origin)──► Next.js ── /bff/gateway/api/** ──► API Gateway :8080 ──► service
Trình duyệt ──────────────────────────── ảnh sản phẩm ─────────► S3 :8333 (đọc ẩn danh)
```

- `src/app/bff/[target]/[...path]/route.ts`: proxy CHỈ cho `/api/**` qua gateway. Không có đường tắt vào
  `/admin/**`/`/actuator/**` của service (khác backoffice) — XSS ở cửa hàng không chạm được endpoint vận hành.
- Ảnh tải thẳng từ object storage (`next.config.ts`: `images.unoptimized` — server Next.js không tải lại ảnh).
- Mô tả sản phẩm render bằng `react-markdown` (không bật HTML thô) → nội dung admin nhập không thể chèn script.

## Cấu trúc

| Đường dẫn | Nội dung |
|---|---|
| `src/app/page.tsx` | Danh mục: Product Service (mặc định) hoặc search-service khi có từ khóa/lọc giá/sắp xếp |
| `src/app/products/[slug]/ProductView.tsx` | Gallery, chọn biến thể, giá/giá gạch, tồn kho theo SKU (Inventory), thêm vào giỏ |
| `src/app/products/[slug]/variants.ts` | Logic chọn biến thể: SKU khớp lựa chọn, giá trị nào hết hàng/không tồn tại |
| `src/app/products/[slug]/Reviews.tsx` | Tóm tắt sao + histogram, lọc theo sao, viết/sửa đánh giá (chỉ người đã mua) |
| `src/app/cart/page.tsx` | Giỏ theo SKU (ảnh, phân loại), Idempotency-Key, checkout |
| `src/app/orders/[id]/OrderDetail.tsx` | Theo dõi Saga (poll 0,7s), snapshot giá + biến thể, link viết đánh giá |
| `src/app/demo/page.tsx` | 6 kịch bản tự động + hướng dẫn kịch bản sự cố hạ tầng |
| `src/lib/api.ts`, `session.ts`, `hooks.ts` | Gọi BFF, phiên khách hàng, tải ví/giỏ |

## Lưu ý bảo mật (vì đây là bản demo)

Access token lưu trong `localStorage` cho đơn giản. Production nên để BFF giữ token trong cookie
`HttpOnly; Secure; SameSite` để JavaScript (và XSS) không đọc được.

Phiên bản: Next.js 16.4.2 (App Router, Cache Components bật), React 19, Tailwind CSS 4, react-markdown 10.
