package com.webshop.product.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body của POST/PUT /api/products. description và active được phép bỏ trống
 * (mặc định "" và true — xử lý ở ProductServiceImpl).
 */
public record ProductRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotNull @PositiveOrZero Long priceVnd,
        Boolean active) {
}
