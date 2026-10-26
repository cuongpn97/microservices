package com.webshop.product.dto.response;

import java.util.UUID;

/**
 * Contract cho Cart/Order Service (GET /api/products/skus/{id}, /skus/batch) — chúng có bản SkuInfo riêng đọc các
 * field này. Đổi tên/bỏ field ở đây là breaking change với service khác.
 *
 * @param active SKU bán được (SKU active VÀ SPU active). Cart/Order chỉ cho mua khi true.
 */
public record SkuInfoResponse(
        UUID id,
        UUID productId,
        String productSlug,
        String productName,
        String skuCode,
        String variantName,
        long priceVnd,
        String imageUrl,
        boolean active) {
}
