package com.webshop.review.service;

import com.webshop.review.dto.request.ReviewRequest;
import com.webshop.review.dto.response.MyReviewResponse;
import com.webshop.review.dto.response.PageResponse;
import com.webshop.review.dto.response.RatingSummaryResponse;
import com.webshop.review.dto.response.ReviewResponse;
import com.webshop.review.enums.ReviewStatus;

import java.util.UUID;

public interface ReviewService {

    /** Đánh giá công khai (PUBLISHED) của một sản phẩm, mới nhất trước. @param rating null = mọi mức sao. */
    PageResponse<ReviewResponse> listPublished(UUID productId, Integer rating, int page, int size);

    RatingSummaryResponse summary(UUID productId);

    MyReviewResponse mine(UUID userId, UUID productId);

    /**
     * Tạo hoặc sửa đánh giá của chính mình (PUT idempotent: gửi lại cùng nội dung ra cùng kết quả).
     * Chỉ người đã có đơn CONFIRMED chứa sản phẩm mới được viết (403 NOT_PURCHASED).
     *
     * @param email từ JWT, chỉ dùng để tạo tên hiển thị đã che bớt; null → "Khách hàng".
     */
    ReviewResponse upsertMine(UUID userId, String email, UUID productId, ReviewRequest request);

    /** Danh sách cho kiểm duyệt (ADMIN), kể cả bài đã ẩn. */
    PageResponse<ReviewResponse> search(ReviewStatus status, UUID productId, int page, int size);

    /** Ẩn/hiện một đánh giá; điểm trung bình được tính lại và phát cho Product Service. */
    ReviewResponse moderate(UUID reviewId, ReviewStatus status);
}
