package com.webshop.search.entity;

/**
 * Một kết quả tìm kiếm đọc từ Elasticsearch (một SPU).
 *
 * @param score điểm liên quan; null khi sắp xếp không theo điểm.
 */
public record ProductSearchHit(
        String productId,
        String slug,
        String name,
        String brand,
        String shortDescription,
        Long minPriceVnd,
        Long maxPriceVnd,
        String imageUrl,
        Double ratingAvg,
        int ratingCount,
        Double score) {
}
