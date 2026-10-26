package com.webshop.product.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Sửa mô tả thay thế (alt) của ảnh — dùng cho trình đọc màn hình và SEO. */
public record ImageUpdateRequest(@NotNull @Size(max = 200) String altText) {
}
