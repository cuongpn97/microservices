package com.webshop.product.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Chi tiết SPU. Storefront chỉ nhận SKU đang bán; backoffice nhận tất cả SKU (kèm cờ active).
 *
 * @param description Markdown — client render bằng renderer không cho HTML thô.
 * @param version     phiên bản SPU (tăng mỗi lần sửa SPU/SKU/ảnh/rating).
 */
public record ProductDetailResponse(
        UUID id,
        String slug,
        String name,
        String brand,
        String shortDescription,
        String description,
        List<SpecificationResponse> specifications,
        List<OptionResponse> options,
        List<SkuResponse> skus,
        List<ImageResponse> images,
        Double ratingAvg,
        int ratingCount,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
