-- Search không còn index tồn kho (docs/design-decisions.md mục 12) → inventory không còn được CDC.
-- Không xóa V101 vì nó đã chạy trên database có sẵn (Flyway validate sẽ báo thiếu migration đã áp dụng);
-- gỡ bằng migration mới. Database cài mới chạy V101 rồi V102 → kết quả như chưa từng có CDC.
--
-- Replication slot dbz_inventory (nếu đã tạo) KHÔNG gỡ được ở đây: cần quyền REPLICATION và slot phải không còn
-- được dùng. Drop tay sau khi xóa connector — xem README mục "Search (CDC)".
DROP PUBLICATION IF EXISTS dbz_inventory;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_user') THEN
        REVOKE SELECT ON inventory_item FROM cdc_user;
        REVOKE USAGE ON SCHEMA public FROM cdc_user;
        EXECUTE format('REVOKE CONNECT ON DATABASE %I FROM cdc_user', current_database());
    END IF;
END
$$;
