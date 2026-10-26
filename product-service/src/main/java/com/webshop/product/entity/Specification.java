package com.webshop.product.entity;

/** Một dòng thông số kỹ thuật ("Kết nối" = "USB-C"). Lưu trong cột JSONB product.specifications. */
public record Specification(String name, String value) {
}
