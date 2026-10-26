package com.webshop.review.dto.request;

import com.webshop.review.enums.ReviewStatus;
import jakarta.validation.constraints.NotNull;

/** Admin ẩn/hiện một đánh giá. */
public record ModerationRequest(@NotNull ReviewStatus status) {
}
