package com.webshop.product.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Thứ tự mới của TẤT CẢ ảnh; ảnh đầu tiên là ảnh đại diện. */
public record ImageOrderRequest(@NotEmpty @Size(max = 10) List<@NotNull UUID> imageIds) {
}
