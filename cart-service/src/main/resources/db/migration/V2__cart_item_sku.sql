-- Giỏ hàng giờ chứa SKU (biến thể bán được) thay vì sản phẩm. Product Service giữ nguyên id cũ cho SKU mặc định
-- của mỗi sản phẩm cũ (xem product-service V4), nên các dòng đang có vẫn đúng — chỉ cần đổi tên cột.
ALTER TABLE cart_item RENAME COLUMN product_id TO sku_id;
