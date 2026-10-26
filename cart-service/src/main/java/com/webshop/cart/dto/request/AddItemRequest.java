package com.webshop.cart.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Cộng thêm quantity vào số đang có trong giỏ (tổng vẫn không vượt 99 — Cart kiểm tra).
 *
 * @param skuId biến thể cụ thể (màu/size...) — giỏ hàng, tồn kho và đơn hàng đều tính theo SKU, không theo SPU.
 */
public record AddItemRequest(
        @NotNull UUID skuId,
        @NotNull @Positive @Max(99) Integer quantity) {
}
