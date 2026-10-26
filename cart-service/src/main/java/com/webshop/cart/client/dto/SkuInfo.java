package com.webshop.cart.client.dto;

import java.util.UUID;

/**
 * Phần response của Product Service (GET /api/products/skus/{id}, /skus/batch) mà Cart cần; Jackson bỏ qua field khác.
 *
 * @param productId SPU chứa SKU này — UI dùng productSlug để link về trang sản phẩm.
 * @param active    SKU bán được (SKU và SPU đều đang bật).
 */
public record SkuInfo(UUID id, UUID productId, String productSlug, String productName, String skuCode,
                      String variantName, long priceVnd, String imageUrl, boolean active) {
}
