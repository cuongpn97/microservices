-- Đánh số 101 (không phải 2): db/demo/V100 đã chạy trên database có sẵn, Flyway từ chối migration số nhỏ hơn
-- bản đã áp dụng (out of order). Migration schema mới của service này phải đánh số > 100.
-- CDC (Debezium → Kafka → search-service): chỉ phát thay đổi của inventory_item (tồn kho khả dụng).
-- reservation/outbox_event là chi tiết nội bộ, không nằm trong publication.
CREATE PUBLICATION dbz_inventory FOR TABLE inventory_item;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_user') THEN
        GRANT USAGE ON SCHEMA public TO cdc_user;
        GRANT SELECT ON inventory_item TO cdc_user;
    END IF;
END
$$;
