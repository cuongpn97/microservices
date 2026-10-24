package com.webshop.search.dto.response;

import java.util.List;

/** Cùng dạng phân trang với GET /api/products (items, page, size, totalElements) để UI dùng chung. */
public record ProductSearchResponse(
        List<ProductHitResponse> items,
        int page,
        int size,
        long totalElements,
        long tookMs) {
}
