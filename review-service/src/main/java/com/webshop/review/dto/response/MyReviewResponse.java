package com.webshop.review.dto.response;

/**
 * Trạng thái đánh giá của người đang đăng nhập với một sản phẩm.
 *
 * @param canReview true khi đã có đơn CONFIRMED chứa sản phẩm này (ghi nhận từ event — có thể trễ vài giây sau
 *                  khi đơn được xác nhận).
 * @param review    đánh giá đã viết (kể cả khi bị ẩn), hoặc null.
 */
public record MyReviewResponse(boolean canReview, ReviewResponse review) {
}
