package com.webshop.product.entity;

import java.util.List;

/**
 * Một trục biến thể của SPU ("Màu" với các giá trị "Đen", "Trắng"). Lưu trong cột JSONB product.options.
 * Thứ tự trục và thứ tự giá trị là thứ tự hiển thị (và thứ tự ghép tên biến thể "Đen / M").
 */
public record ProductOption(String name, List<String> values) {

    public ProductOption {
        values = List.copyOf(values);
    }
}
