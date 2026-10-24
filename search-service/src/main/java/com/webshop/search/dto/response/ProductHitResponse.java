package com.webshop.search.dto.response;

/**
 * Không có tồn kho: lấy số thật từ GET /api/inventory/batch cho các sản phẩm đang hiển thị.
 * priceVnd chỉ để hiển thị — checkout luôn đọc lại giá từ Product Service.
 */
public record ProductHitResponse(
        String productId,
        String name,
        String description,
        long priceVnd,
        Double score) {
}
