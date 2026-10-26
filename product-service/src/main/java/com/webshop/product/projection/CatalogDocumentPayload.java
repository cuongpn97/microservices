package com.webshop.product.projection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Nội dung cột catalog_document.payload — HỢP ĐỒNG với search-service (đọc qua CDC). Thêm field là an toàn
 * (search bỏ qua field lạ); đổi tên/bỏ field là breaking change → tăng {@link CatalogProjection#SCHEMA_VERSION}.
 *
 * @param minPriceVnd       giá thấp nhất trong các SKU bán được; null khi không có SKU nào bán được.
 * @param optionValues      mọi giá trị option ("Đen", "XL"...) — để tìm "áo đen" ra đúng SPU.
 * @param sellableSkuCount  0 nghĩa là SPU đang hiển thị nhưng chưa mua được biến thể nào.
 */
public record CatalogDocumentPayload(
        UUID productId,
        String slug,
        String name,
        String brand,
        String shortDescription,
        String description,
        boolean active,
        Long minPriceVnd,
        Long maxPriceVnd,
        String imageUrl,
        Double ratingAvg,
        int ratingCount,
        List<String> skuCodes,
        List<String> optionValues,
        int sellableSkuCount,
        Instant updatedAt) {
}
