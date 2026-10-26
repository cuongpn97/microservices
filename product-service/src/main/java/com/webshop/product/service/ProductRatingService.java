package com.webshop.product.service;

import java.util.UUID;

/** Nhận tóm tắt đánh giá từ review-service (event ProductRatingChanged) để hiển thị và đẩy sang search. */
public interface ProductRatingService {

    /**
     * Bắt buộc chạy trong transaction của consumer (cùng Inbox). Bản có version không lớn hơn bản đang có bị bỏ qua
     * (event đến trễ/đảo thứ tự). SPU không tồn tại → bỏ qua (không retry vô ích).
     */
    void applyRating(UUID productId, int ratingCount, long ratingSum, long version);
}
