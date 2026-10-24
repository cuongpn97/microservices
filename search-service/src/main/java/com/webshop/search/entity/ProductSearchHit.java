package com.webshop.search.entity;

/**
 * Một kết quả tìm kiếm đọc từ Elasticsearch.
 *
 * @param score điểm liên quan; null khi sắp xếp không theo điểm.
 */
public record ProductSearchHit(
        String productId,
        String name,
        String description,
        long priceVnd,
        Double score) {
}
