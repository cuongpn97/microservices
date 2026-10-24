package com.webshop.product.dto.response;

import java.util.List;

/** Tự định nghĩa thay vì trả thẳng Page của Spring: JSON của Page có nhiều field nội bộ và không ổn định. */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements) {
}
