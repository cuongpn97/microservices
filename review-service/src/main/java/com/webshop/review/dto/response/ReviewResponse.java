package com.webshop.review.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Không có userId/email: người xem chỉ thấy tên đã che bớt.
 *
 * @param variantName biến thể người viết đã mua ("Đen / M"); rỗng nếu sản phẩm chỉ có một biến thể.
 * @param status      PUBLISHED/HIDDEN — storefront chỉ nhận PUBLISHED (trừ đánh giá của chính mình).
 */
public record ReviewResponse(
        UUID id,
        UUID productId,
        String authorName,
        int rating,
        String title,
        String content,
        String variantName,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
