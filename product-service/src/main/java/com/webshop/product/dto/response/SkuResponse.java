package com.webshop.product.dto.response;

import java.util.Map;
import java.util.UUID;

/**
 * @param variantName "Đen / M" — ghép giá trị thuộc tính theo thứ tự option của SPU.
 * @param imageUrl    ảnh riêng của biến thể, hoặc null (client dùng ảnh SPU).
 * @param sellable    active của SKU VÀ của SPU.
 */
public record SkuResponse(
        UUID id,
        String skuCode,
        Map<String, String> attributes,
        String variantName,
        long priceVnd,
        Long compareAtPriceVnd,
        UUID imageId,
        String imageUrl,
        boolean active,
        boolean sellable) {
}
