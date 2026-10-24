package com.webshop.cart.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** Cộng thêm quantity vào số đang có trong giỏ (tổng vẫn không vượt 99 — Cart kiểm tra). */
public record AddItemRequest(
        @NotNull UUID productId,
        @NotNull @Positive @Max(99) Integer quantity) {
}
