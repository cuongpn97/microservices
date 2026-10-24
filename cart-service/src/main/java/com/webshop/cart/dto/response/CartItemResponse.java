package com.webshop.cart.dto.response;

import java.util.UUID;

/**
 * name/unitPriceVnd/lineTotalVnd = null khi giỏ trả về không kèm giá (pricing=false, hoặc Product Service lỗi).
 *
 * @param active false nếu sản phẩm đã ngừng bán — không tính vào tổng tạm tính.
 */
public record CartItemResponse(
        UUID productId,
        int quantity,
        String name,
        Long unitPriceVnd,
        Long lineTotalVnd,
        boolean active) {
}
