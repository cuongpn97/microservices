package com.webshop.product.service;

import com.webshop.product.dto.response.PageResponse;
import com.webshop.product.dto.response.ProductDetailResponse;
import com.webshop.product.dto.response.ProductSummaryResponse;
import com.webshop.product.dto.response.SkuInfoResponse;

import java.util.List;
import java.util.UUID;

/** Đọc catalog công khai (storefront) và tra cứu SKU cho Cart/Order Service. */
public interface ProductQueryService {

    /** Chỉ SPU đang bán, sắp theo tên. */
    PageResponse<ProductSummaryResponse> listActive(int page, int size);

    /**
     * Chi tiết SPU theo id hoặc slug, chỉ kèm SKU bán được. SPU đang tắt (bản nháp, ngừng bán) → 404:
     * storefront không được lộ sản phẩm chưa công bố.
     */
    ProductDetailResponse getPublished(String idOrSlug);

    /** Trả cả SKU ngừng bán (active=false) để Cart/Order tự kiểm tra trạng thái và báo lỗi rõ ràng. */
    SkuInfoResponse getSku(UUID skuId);

    /** Tra cứu nhiều SKU một lần (Cart hiển thị giỏ, Order định giá lúc checkout); id không tồn tại bị bỏ qua. */
    List<SkuInfoResponse> getSkus(List<UUID> skuIds);
}
