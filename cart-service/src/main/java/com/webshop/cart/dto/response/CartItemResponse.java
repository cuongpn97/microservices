package com.webshop.cart.dto.response;

import java.util.UUID;

/**
 * Các field lấy từ Product Service (productId trở đi, trừ quantity/active) = null khi giỏ trả về không kèm giá
 * (pricing=false, hoặc Product Service lỗi).
 *
 * @param variantName "Đen / M"; rỗng khi sản phẩm chỉ có một biến thể.
 * @param active      false nếu SKU đã ngừng bán — không tính vào tổng tạm tính.
 */
public record CartItemResponse(
        UUID skuId,
        int quantity,
        UUID productId,
        String productSlug,
        String productName,
        String skuCode,
        String variantName,
        String imageUrl,
        Long unitPriceVnd,
        Long lineTotalVnd,
        boolean active) {
}
