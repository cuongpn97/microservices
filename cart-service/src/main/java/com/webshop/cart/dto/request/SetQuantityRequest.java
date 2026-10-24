package com.webshop.cart.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Đặt số lượng tuyệt đối (khác AddItemRequest là cộng dồn). */
public record SetQuantityRequest(@NotNull @Positive @Max(99) Integer quantity) {
}
