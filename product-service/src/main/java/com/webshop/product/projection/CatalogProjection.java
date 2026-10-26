package com.webshop.product.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.product.entity.Product;
import com.webshop.product.entity.ProductOption;
import com.webshop.product.entity.ProductSku;
import com.webshop.product.mapper.CatalogMapper;
import com.webshop.product.repository.CatalogDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LongSummaryStatistics;

/**
 * Ghi lại catalog_document của một SPU sau MỖI thay đổi aggregate (SPU, SKU, ảnh, rating) — trong cùng transaction.
 * Debezium đọc bảng này (và chỉ bảng này) từ WAL → Kafka → search-service.
 *
 * <p>Tương tự Transactional Outbox, khác ở chỗ lưu TRẠNG THÁI (một dòng mỗi SPU, ghi đè) thay vì chuỗi event: CDC
 * snapshot ban đầu đọc được trạng thái đầy đủ, và Kafka compaction giữ đúng bản mới nhất mỗi sản phẩm.</p>
 */
@Component
@RequiredArgsConstructor
public class CatalogProjection {

    /** Tăng khi đổi cấu trúc payload → CatalogBackfill dựng lại mọi document lúc khởi động. */
    public static final int SCHEMA_VERSION = 1;

    private final CatalogDocumentRepository catalogDocumentRepository;
    private final CatalogMapper catalogMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(Product p) {
        LongSummaryStatistics prices = catalogMapper.priceStats(p, ProductSku::isSellable);
        CatalogDocumentPayload payload = new CatalogDocumentPayload(
                p.getId(), p.getSlug(), p.getName(), p.getBrand(), p.getShortDescription(), p.getDescription(),
                p.isActive(),
                prices.getCount() == 0 ? null : prices.getMin(),
                prices.getCount() == 0 ? null : prices.getMax(),
                p.getPrimaryImage().map(catalogMapper::url).orElse(null),
                p.getRatingAverage(), p.getRatingCount(),
                p.getSkus().stream().map(ProductSku::getSkuCode).toList(),
                p.getOptions().stream().map(ProductOption::values).flatMap(java.util.List::stream).distinct().toList(),
                (int) prices.getCount(),
                p.getUpdatedAt());
        try {
            catalogDocumentRepository.upsert(p.getId(), SCHEMA_VERSION, objectMapper.writeValueAsString(payload),
                    clock.instant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize catalog document for " + p.getId(), e);
        }
    }
}
