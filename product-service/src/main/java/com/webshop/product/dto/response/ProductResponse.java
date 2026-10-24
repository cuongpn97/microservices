package com.webshop.product.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Contract trả về cho client VÀ cho Cart/Order Service (chúng có bản ProductInfo riêng đọc các field này).
 * Đổi tên/bỏ field ở đây là breaking change với các service khác.
 */
public record ProductResponse(
        UUID id,
        String name,
        String description,
        long priceVnd,
        boolean active,
        Instant updatedAt) {
}
