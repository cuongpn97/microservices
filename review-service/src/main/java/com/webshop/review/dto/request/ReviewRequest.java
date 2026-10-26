package com.webshop.review.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body của PUT /api/reviews/products/{productId}/mine — tạo mới hoặc sửa đánh giá của chính mình. */
public record ReviewRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        @Size(max = 150) String title,
        @NotBlank @Size(min = 10, max = 4000) String content) {
}
