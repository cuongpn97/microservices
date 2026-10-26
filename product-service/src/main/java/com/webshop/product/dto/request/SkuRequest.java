package com.webshop.product.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * Body của POST/PUT /api/products/manage/{productId}/skus.
 *
 * @param attributes        một giá trị cho MỖI option của SPU, ví dụ {"Màu":"Đen","Size":"M"}; {} khi SPU không có option.
 * @param compareAtPriceVnd giá gạch ngang, phải lớn hơn priceVnd; chỉ để hiển thị.
 * @param imageId           ảnh riêng của biến thể (phải là ảnh của chính SPU); null = dùng ảnh đại diện SPU.
 * @param active            mặc định true.
 */
public record SkuRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Z0-9][A-Z0-9._-]*$", message = "chỉ gồm chữ IN HOA, số, . _ -")
        String skuCode,
        @Size(max = 3) Map<@NotBlank String, @NotBlank String> attributes,
        @NotNull @PositiveOrZero Long priceVnd,
        @PositiveOrZero Long compareAtPriceVnd,
        UUID imageId,
        Boolean active) {
}
