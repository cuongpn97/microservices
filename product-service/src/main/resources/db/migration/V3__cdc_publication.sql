-- CDC (Debezium → Kafka → search-service). Publication chỉ chứa bảng products: Debezium KHÔNG thấy outbox_event
-- hay bảng nào khác. Chỉ owner của bảng mới tạo được publication, nên nó nằm ở đây thay vì script init.
CREATE PUBLICATION dbz_product FOR TABLE products;

-- Debezium cần SELECT để chụp snapshot ban đầu. Role cdc_user chỉ tồn tại khi chạy với profile search
-- (infra/postgres/init/02-create-cdc-user.sh); test/môi trường không có CDC thì bỏ qua.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_user') THEN
        GRANT USAGE ON SCHEMA public TO cdc_user;
        GRANT SELECT ON products TO cdc_user;
    END IF;
END
$$;
