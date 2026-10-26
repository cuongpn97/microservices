package com.webshop.order.dto.response;

import java.util.UUID;

/**
 * Snapshot một dòng đơn hàng.
 *
 * @param productId   SPU — null với đơn tạo trước khi có SKU.
 * @param variantName "Đen / M"; rỗng khi sản phẩm chỉ có một biến thể.
 */
public record OrderItemResponse(
        UUID skuId,
        UUID productId,
        String productName,
        String skuCode,
        String variantName,
        long unitPriceVnd,
        int quantity,
        long lineTotalVnd) {
}
