-- Dữ liệu demo (chỉ nạp khi FLYWAY_LOCATIONS chứa classpath:db/demo — mặc định có).
-- productId trùng với seed của Product Service; đây là đồng bộ dữ liệu demo, KHÔNG phải truy cập DB chéo.
INSERT INTO inventory_item (product_id, stock_on_hand, reserved_quantity, updated_at) VALUES
('11111111-1111-1111-1111-111111111111', 50, 0, now()),
('22222222-2222-2222-2222-222222222222', 100, 0, now()),
('33333333-3333-3333-3333-333333333333', 2, 0, now()),
('44444444-4444-4444-4444-444444444444', 10, 0, now())
ON CONFLICT (product_id) DO NOTHING;
