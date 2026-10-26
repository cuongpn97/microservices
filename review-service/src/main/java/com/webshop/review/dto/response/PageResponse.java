package com.webshop.review.dto.response;

import java.util.List;

/** Cùng dạng phân trang với Product Service (items, page, size, totalElements). */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements) {
}
