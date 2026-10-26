package com.webshop.search.entity;

import java.util.List;

/**
 * Nội dung một SPU cần ghi vào index — đọc từ cột payload của bảng catalog_document (product-service) trong event CDC.
 * Không phải JPA entity: service này không có DB quan hệ, "kho dữ liệu" là Elasticsearch.
 *
 * @param minPriceVnd giá thấp nhất trong các SKU bán được; null khi chưa có SKU nào bán được.
 * @param version     cột version của catalog_document (tăng mỗi lần ghi) — dùng để bỏ qua bản cũ đến muộn.
 * @param updatedAt   giữ nguyên chuỗi product-service gửi, không parse.
 */
public record ProductDocument(
        String id,
        String slug,
        String name,
        String brand,
        String shortDescription,
        String description,
        boolean active,
        Long minPriceVnd,
        Long maxPriceVnd,
        String imageUrl,
        Double ratingAvg,
        int ratingCount,
        List<String> skuCodes,
        List<String> optionValues,
        int sellableSkuCount,
        long version,
        String updatedAt) {
}
