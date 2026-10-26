package com.webshop.review.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.review.dto.request.ReviewRequest;
import com.webshop.review.dto.response.MyReviewResponse;
import com.webshop.review.dto.response.PageResponse;
import com.webshop.review.dto.response.RatingSummaryResponse;
import com.webshop.review.dto.response.ReviewResponse;
import com.webshop.review.entity.ProductRating;
import com.webshop.review.entity.Purchase;
import com.webshop.review.entity.Review;
import com.webshop.review.enums.ReviewStatus;
import com.webshop.review.mapper.ReviewMapper;
import com.webshop.review.repository.ProductRatingRepository;
import com.webshop.review.repository.PurchaseRepository;
import com.webshop.review.repository.ReviewRepository;
import com.webshop.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // mặc định chỉ đọc; method ghi tự khai báo @Transactional riêng
public class ReviewServiceImpl implements ReviewService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ReviewRepository reviewRepository;
    private final PurchaseRepository purchaseRepository;
    private final ProductRatingRepository productRatingRepository;
    private final RatingRecalculator ratingRecalculator;
    private final ReviewMapper reviewMapper;
    private final Clock clock;

    @Override
    public PageResponse<ReviewResponse> listPublished(UUID productId, Integer rating, int page, int size) {
        return reviewMapper.toPage(reviewRepository.findForProduct(productId, ReviewStatus.PUBLISHED,
                rating == null ? null : rating.shortValue(), PageRequest.of(page, size, NEWEST_FIRST)));
    }

    @Override
    public RatingSummaryResponse summary(UUID productId) {
        return productRatingRepository.findById(productId).map(reviewMapper::toSummary)
                .orElseGet(() -> reviewMapper.emptySummary(productId));
    }

    @Override
    public MyReviewResponse mine(UUID userId, UUID productId) {
        boolean purchased = purchaseRepository.findFirstByUserIdAndProductIdOrderByPurchasedAtDesc(userId, productId)
                .isPresent();
        ReviewResponse review = reviewRepository.findByProductIdAndUserId(productId, userId)
                .map(reviewMapper::toResponse).orElse(null);
        return new MyReviewResponse(purchased, review);
    }

    @Override
    @Transactional
    public ReviewResponse upsertMine(UUID userId, String email, UUID productId, ReviewRequest request) {
        Purchase purchase = purchaseRepository.findFirstByUserIdAndProductIdOrderByPurchasedAtDesc(userId, productId)
                .orElseThrow(() -> ApiException.forbidden("NOT_PURCHASED",
                        "Only customers with a confirmed order for this product can review it"));
        Instant now = clock.instant();
        // Khóa tóm tắt rating TRƯỚC khi ghi review: các thay đổi review của cùng sản phẩm chạy tuần tự.
        ProductRating rating = ratingRecalculator.lock(productId);
        Review review = reviewRepository.findByProductIdAndUserId(productId, userId)
                .orElseGet(() -> new Review(UUID.randomUUID(), productId, userId, displayName(email),
                        purchase.getVariantName(), now));
        review.edit(request.rating(), blankToNull(request.title()), request.content().strip(), now);
        reviewRepository.saveAndFlush(review);
        ratingRecalculator.recalculate(rating);
        return reviewMapper.toResponse(review);
    }

    @Override
    public PageResponse<ReviewResponse> search(ReviewStatus status, UUID productId, int page, int size) {
        return reviewMapper.toPage(reviewRepository.search(status, productId, PageRequest.of(page, size, NEWEST_FIRST)));
    }

    @Override
    @Transactional
    public ReviewResponse moderate(UUID reviewId, ReviewStatus status) {
        UUID productId = reviewRepository.findProductIdById(reviewId)
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "Review not found: " + reviewId));
        ProductRating rating = ratingRecalculator.lock(productId);
        // Nạp review SAU khi giữ khóa → thấy bản mới nhất nếu người viết vừa sửa song song.
        Review review = reviewRepository.findById(reviewId).orElseThrow();
        if (review.getStatus() != status) {
            review.moderate(status, clock.instant());
            reviewRepository.saveAndFlush(review);
            ratingRecalculator.recalculate(rating);
        }
        return reviewMapper.toResponse(review);
    }

    /** "nguyenvana@gmail.com" → "ng***@gmail.com": đủ để phân biệt, không lộ email đầy đủ. */
    static String displayName(String email) {
        if (email == null || !email.contains("@")) {
            return "Khách hàng";
        }
        String local = email.substring(0, email.indexOf('@'));
        String domain = email.substring(email.indexOf('@'));
        return local.substring(0, Math.min(2, local.length())) + "***" + domain;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
