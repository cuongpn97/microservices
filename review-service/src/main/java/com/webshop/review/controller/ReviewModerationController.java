package com.webshop.review.controller;

import com.webshop.review.dto.request.ModerationRequest;
import com.webshop.review.dto.response.PageResponse;
import com.webshop.review.dto.response.ReviewResponse;
import com.webshop.review.enums.ReviewStatus;
import com.webshop.review.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Kiểm duyệt đánh giá cho backoffice (chỉ ADMIN — chặn ở SecurityConfig và @PreAuthorize). */
@RestController
@RequestMapping("/api/reviews/moderation")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@RequiredArgsConstructor
public class ReviewModerationController {

    private final ReviewService reviewService;

    @GetMapping
    public PageResponse<ReviewResponse> search(@RequestParam(required = false) ReviewStatus status,
                                               @RequestParam(required = false) UUID productId,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewService.search(status, productId, page, size);
    }

    @PutMapping("/{reviewId}/status")
    public ReviewResponse moderate(@PathVariable UUID reviewId, @Valid @RequestBody ModerationRequest request) {
        return reviewService.moderate(reviewId, request.status());
    }
}
