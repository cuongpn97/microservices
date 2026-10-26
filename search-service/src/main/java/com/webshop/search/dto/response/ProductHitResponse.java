package com.webshop.search.dto.response;

/**
 * Một SPU trong kết quả tìm kiếm — cùng các field với ProductSummaryResponse của Product Service để UI dùng chung thẻ
 * sản phẩm. Không có tồn kho; giá chỉ để hiển thị (checkout luôn đọc lại giá SKU từ Product Service).
 *
 * @param minPriceVnd "Từ ..." — giá thấp nhất trong các biến thể đang bán.
 */
public record ProductHitResponse(
        String productId,
        String slug,
        String name,
        String brand,
        String shortDescription,
        Long minPriceVnd,
        Long maxPriceVnd,
        String imageUrl,
        Double ratingAvg,
        int ratingCount,
        Double score) {
}
