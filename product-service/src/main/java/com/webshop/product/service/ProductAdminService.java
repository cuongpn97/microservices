package com.webshop.product.service;

import com.webshop.product.dto.request.ProductRequest;
import com.webshop.product.dto.request.SkuRequest;
import com.webshop.product.dto.response.PageResponse;
import com.webshop.product.dto.response.ProductDetailResponse;
import com.webshop.product.dto.response.ProductSummaryResponse;

import java.util.UUID;

/**
 * Quản trị catalog (backoffice, chỉ ADMIN). Mọi thao tác ghi khóa dòng SPU, ghi lại catalog_document trong cùng
 * transaction, và trả về chi tiết đầy đủ (kể cả SKU ngừng bán) để UI cập nhật ngay.
 * Đổi giá chỉ ảnh hưởng đơn hàng TƯƠNG LAI: đơn đã checkout giữ snapshot giá tại thời điểm checkout.
 */
public interface ProductAdminService {

    /** @param q tìm theo tên/slug (không phân biệt hoa thường); null = tất cả. */
    PageResponse<ProductSummaryResponse> search(String q, Boolean active, int page, int size);

    ProductDetailResponse get(UUID productId);

    ProductDetailResponse create(ProductRequest request);

    ProductDetailResponse update(UUID productId, ProductRequest request);

    ProductDetailResponse addSku(UUID productId, SkuRequest request);

    ProductDetailResponse updateSku(UUID productId, UUID skuId, SkuRequest request);
}
