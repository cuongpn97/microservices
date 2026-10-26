package com.webshop.product.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng trong danh sách sản phẩm (storefront và backoffice).
 *
 * @param minPriceVnd giá thấp nhất trong các SKU đang bán; null khi không có SKU nào bán được.
 * @param imageUrl    ảnh đại diện (ảnh đầu tiên); null khi chưa có ảnh.
 * @param ratingAvg   null khi chưa có đánh giá.
 */
public record ProductSummaryResponse(
        UUID id,
        String slug,
        String name,
        String brand,
        String shortDescription,
        Long minPriceVnd,
        Long maxPriceVnd,
        String imageUrl,
        Double ratingAvg,
        int ratingCount,
        int skuCount,
        boolean active,
        Instant updatedAt) {
}
