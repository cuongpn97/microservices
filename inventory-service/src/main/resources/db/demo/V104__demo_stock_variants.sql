-- Dữ liệu demo cho các SKU biến thể (product-service V5). skuId trùng seed của Product Service; đây là đồng bộ dữ liệu
-- demo, KHÔNG phải truy cập DB chéo. Đánh số > 103 vì chạy SAU khi cột đã đổi thành sku_id.
INSERT INTO inventory_item (sku_id, stock_on_hand, reserved_quantity, updated_at) VALUES
-- Bàn phím: Blue, Brown (Red = 1111...1111 đã có từ V100)
('11111111-1111-1111-1111-000000000002', 30, 0, now()),
('11111111-1111-1111-1111-000000000003', 0, 0, now()),
-- Chuột trắng
('22222222-2222-2222-2222-000000000002', 40, 0, now()),
-- Áo thun Đen/Trắng x M/L/XL
('66666666-6666-6666-6666-000000000001', 20, 0, now()),
('66666666-6666-6666-6666-000000000002', 25, 0, now()),
('66666666-6666-6666-6666-000000000003', 3, 0, now()),
('66666666-6666-6666-6666-000000000004', 20, 0, now()),
('66666666-6666-6666-6666-000000000005', 0, 0, now()),
('66666666-6666-6666-6666-000000000006', 10, 0, now()),
-- Tai nghe Đen/Bạc
('77777777-7777-7777-7777-000000000001', 15, 0, now()),
('77777777-7777-7777-7777-000000000002', 8, 0, now())
ON CONFLICT (sku_id) DO NOTHING;
