package com.webshop.product.projection;

import com.webshop.product.repository.CatalogDocumentRepository;
import com.webshop.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Lúc khởi động: dựng catalog_document cho SPU chưa có document (dữ liệu tạo bằng migration SQL) hoặc có document
 * theo schema cũ (sau khi tăng {@link CatalogProjection#SCHEMA_VERSION}). Chạy TRƯỚC khi service báo "ready"
 * (ApplicationRunner chạy trước readiness), nên connector Debezium đăng ký sau đó snapshot được dữ liệu đầy đủ.
 *
 * <p>Mỗi SPU một transaction ngắn, khóa dòng như mọi thay đổi khác → chạy song song với request admin vẫn đúng.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogBackfill implements ApplicationRunner {

    private static final int BATCH = 100;

    private final CatalogDocumentRepository catalogDocumentRepository;
    private final ProductRepository productRepository;
    private final CatalogProjection catalogProjection;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        List<UUID> stale;
        while (!(stale = catalogDocumentRepository.findStale(CatalogProjection.SCHEMA_VERSION, BATCH)).isEmpty()) {
            for (UUID id : stale) {
                transactionTemplate.executeWithoutResult(status ->
                        productRepository.lockById(id).ifPresent(catalogProjection::refresh));
            }
            total += stale.size();
        }
        if (total > 0) {
            log.info("Catalog backfill rebuilt {} document(s) (schema v{})", total, CatalogProjection.SCHEMA_VERSION);
        }
    }
}
