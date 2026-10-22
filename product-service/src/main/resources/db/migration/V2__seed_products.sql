-- Dữ liệu demo với UUID cố định để README/curl/Postman tham chiếu được.
-- Inventory Service seed tồn kho cho cùng các productId trong migration RIÊNG của nó (không truy cập DB này).
INSERT INTO products (id, name, description, price_vnd, active, created_at, updated_at) VALUES
('11111111-1111-1111-1111-111111111111', 'Mechanical Keyboard', 'Bàn phím cơ 87 phím', 1500000, TRUE, now(), now()),
('22222222-2222-2222-2222-222222222222', 'Wireless Mouse', 'Chuột không dây 2.4GHz', 450000, TRUE, now(), now()),
('33333333-3333-3333-3333-333333333333', '27-inch Monitor', 'Màn hình 27 inch 2K', 6900000, TRUE, now(), now()),
('44444444-4444-4444-4444-444444444444', 'Premium Laptop', 'Laptop giá cao để demo thiếu credit', 25000000, TRUE, now(), now()),
('55555555-5555-5555-5555-555555555555', 'Discontinued Item', 'Sản phẩm ngừng bán (inactive)', 100000, FALSE, now(), now());
