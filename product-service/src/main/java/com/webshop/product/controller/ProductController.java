package com.webshop.product.controller;

import com.webshop.product.dto.response.PageResponse;
import com.webshop.product.dto.response.ProductDetailResponse;
import com.webshop.product.dto.response.ProductSummaryResponse;
import com.webshop.product.service.ProductQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog công khai cho storefront (không cần đăng nhập). Controller chỉ lo HTTP: route, validate input, status code.
 * Ghi dữ liệu nằm ở {@link ProductAdminController} (/api/products/manage, chỉ ADMIN).
 */
@RestController
@RequestMapping("/api/products")
@Validated // bật validate cho @RequestParam/@PathVariable (@Min, @Max, @Size...)
@RequiredArgsConstructor
public class ProductController {

    private final ProductQueryService productQueryService;

    @GetMapping
    public PageResponse<ProductSummaryResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return productQueryService.listActive(page, size);
    }

    /** Nhận id (UUID) hoặc slug — storefront dùng slug cho URL đẹp (/products/ao-thun-cotton-basic). */
    @GetMapping("/{idOrSlug}")
    public ProductDetailResponse get(@PathVariable String idOrSlug) {
        return productQueryService.getPublished(idOrSlug);
    }
}
