-- Tồn kho theo SKU (biến thể) thay vì theo sản phẩm. Product Service giữ nguyên id cũ cho SKU mặc định của mỗi sản
-- phẩm cũ (product-service V4) → dữ liệu đang có vẫn đúng, chỉ đổi tên cột. Không truy cập database của service khác.
ALTER TABLE inventory_item RENAME COLUMN product_id TO sku_id;
ALTER TABLE reservation_line RENAME COLUMN product_id TO sku_id;
