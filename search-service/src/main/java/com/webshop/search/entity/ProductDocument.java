package com.webshop.search.entity;

/**
 * Nội dung một sản phẩm cần ghi vào index — dựng từ ảnh dòng (row image) của bảng products trong event CDC.
 * Không phải JPA entity: service này không có DB quan hệ, "kho dữ liệu" là Elasticsearch.
 *
 * @param version   cột @Version của bảng products — dùng để bỏ qua bản cũ đến muộn (chống ghi lùi).
 * @param updatedAt giữ nguyên chuỗi Debezium gửi, không parse.
 */
public record ProductDocument(
        String id,
        String name,
        String description,
        long priceVnd,
        boolean active,
        long version,
        String updatedAt) {
}
