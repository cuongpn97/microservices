-- Dòng đơn hàng theo SKU (biến thể). Product Service giữ nguyên id cũ cho SKU mặc định của mỗi sản phẩm cũ
-- (product-service V4) → cột product_id cũ thực chất đã là sku_id, chỉ cần đổi tên (khóa chính đi theo cột).
ALTER TABLE order_item RENAME COLUMN product_id TO sku_id;

-- Thêm snapshot SPU + biến thể. Đơn cũ để NULL: order_item bất biến (trigger cấm UPDATE) và Order Service không biết
-- SPU của SKU cũ (dữ liệu của Product Service). Đơn mới luôn có giá trị — ứng dụng bảo đảm.
-- ADD COLUMN không chạy trigger theo dòng nên không vướng trg_order_item_immutable.
ALTER TABLE order_item ADD COLUMN product_id   UUID;
ALTER TABLE order_item ADD COLUMN sku_code     VARCHAR(64);
ALTER TABLE order_item ADD COLUMN variant_name VARCHAR(200);
