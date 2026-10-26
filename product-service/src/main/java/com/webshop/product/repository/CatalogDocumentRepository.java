package com.webshop.product.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bảng catalog_document (được CDC sang search-service). Dùng JdbcTemplate vì đây là một UPSERT có điều kiện, không
 * phải entity có vòng đời; JpaTransactionManager cho JdbcTemplate dùng chung transaction với thay đổi JPA.
 */
@Repository
@RequiredArgsConstructor
public class CatalogDocumentRepository {

    private final JdbcTemplate jdbc;

    /**
     * Ghi document mới cho một sản phẩm, version tăng 1 so với bản đang có. MANDATORY: document phải commit CÙNG
     * transaction với thay đổi catalog — không có lúc nào dữ liệu đã đổi mà document chưa đổi (và ngược lại).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsert(UUID productId, int schemaVersion, String payloadJson, Instant now) {
        jdbc.update("""
                        INSERT INTO catalog_document (product_id, version, schema_version, payload, updated_at)
                        VALUES (?, 1, ?, ?::jsonb, ?)
                        ON CONFLICT (product_id) DO UPDATE
                        SET version = catalog_document.version + 1,
                            schema_version = EXCLUDED.schema_version,
                            payload = EXCLUDED.payload,
                            updated_at = EXCLUDED.updated_at
                        """,
                productId, schemaVersion, payloadJson, Timestamp.from(now));
    }

    /** SPU chưa có document hoặc document dựng bằng schema cũ hơn → cần dựng lại (lúc khởi động). */
    public List<UUID> findStale(int currentSchemaVersion, int limit) {
        return jdbc.queryForList("""
                        SELECT p.id FROM product p
                        LEFT JOIN catalog_document d ON d.product_id = p.id
                        WHERE d.product_id IS NULL OR d.schema_version < ?
                        ORDER BY p.id
                        LIMIT ?
                        """,
                UUID.class, currentSchemaVersion, limit);
    }
}
