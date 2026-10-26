package com.webshop.order.client.dto;

import java.util.UUID;

/**
 * Phần response của GET /api/products/skus/batch (Product Service) mà Order cần: giá có thẩm quyền, trạng thái, và
 * thông tin để chụp snapshot dòng đơn hàng (tên SPU, mã SKU, tên biến thể).
 *
 * @param active SKU bán được (SKU và SPU đều đang bật).
 */
public record SkuInfo(UUID id, UUID productId, String productName, String skuCode, String variantName, long priceVnd,
                      boolean active) {
}
