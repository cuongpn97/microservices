package com.webshop.review.dto.response;

import java.util.Map;
import java.util.UUID;

/**
 * @param ratingAvg null khi chưa có đánh giá.
 * @param histogram số sao → số đánh giá, từ 5 xuống 1.
 */
public record RatingSummaryResponse(
        UUID productId,
        int ratingCount,
        Double ratingAvg,
        Map<Integer, Integer> histogram) {
}
