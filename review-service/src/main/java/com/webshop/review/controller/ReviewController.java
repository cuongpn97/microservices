package com.webshop.review.controller;

import com.webshop.common.security.WebshopJwt;
import com.webshop.review.dto.request.ReviewRequest;
import com.webshop.review.dto.response.MyReviewResponse;
import com.webshop.review.dto.response.PageResponse;
import com.webshop.review.dto.response.RatingSummaryResponse;
import com.webshop.review.dto.response.ReviewResponse;
import com.webshop.review.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Đánh giá theo sản phẩm (productId = SPU). Đọc công khai; viết cần đăng nhập và đã mua.
 * userId luôn lấy từ JWT ("sub"), không bao giờ từ client.
 */
@RestController
@RequestMapping("/api/reviews/products/{productId}")
@Validated
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public PageResponse<ReviewResponse> list(@PathVariable UUID productId,
                                             @RequestParam(required = false) @Min(1) @Max(5) Integer rating,
                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return reviewService.listPublished(productId, rating, page, size);
    }

    @GetMapping("/summary")
    public RatingSummaryResponse summary(@PathVariable UUID productId) {
        return reviewService.summary(productId);
    }

    @GetMapping("/mine")
    public MyReviewResponse mine(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return reviewService.mine(WebshopJwt.userId(jwt), productId);
    }

    /** PUT (không phải POST): mỗi người một đánh giá cho mỗi sản phẩm → gửi lại = ghi đè, an toàn khi retry. */
    @PutMapping("/mine")
    public ReviewResponse upsert(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                                 @Valid @RequestBody ReviewRequest request) {
        return reviewService.upsertMine(WebshopJwt.userId(jwt), jwt.getClaimAsString(WebshopJwt.EMAIL_CLAIM),
                productId, request);
    }
}
