package com.webshop.product.controller;

import com.webshop.product.dto.request.ProductRequest;
import com.webshop.product.dto.request.SkuRequest;
import com.webshop.product.dto.response.PageResponse;
import com.webshop.product.dto.response.ProductDetailResponse;
import com.webshop.product.dto.response.ProductSummaryResponse;
import com.webshop.product.service.ProductAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Quản trị catalog cho backoffice. Đi qua API Gateway như API thường (đây là nghiệp vụ, không phải endpoint vận hành
 * /admin/**) nhưng chỉ ADMIN: chặn ở SecurityConfig VÀ @PreAuthorize (defense in depth).
 */
@RestController
@RequestMapping("/api/products/manage")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@RequiredArgsConstructor
public class ProductAdminController {

    private final ProductAdminService productAdminService;

    @GetMapping
    public PageResponse<ProductSummaryResponse> search(@RequestParam(required = false) @Size(max = 100) String q,
                                                       @RequestParam(required = false) Boolean active,
                                                       @RequestParam(defaultValue = "0") @Min(0) int page,
                                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return productAdminService.search(q, active, page, size);
    }

    @GetMapping("/{productId}")
    public ProductDetailResponse get(@PathVariable UUID productId) {
        return productAdminService.get(productId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDetailResponse create(@Valid @RequestBody ProductRequest request) {
        return productAdminService.create(request);
    }

    @PutMapping("/{productId}")
    public ProductDetailResponse update(@PathVariable UUID productId, @Valid @RequestBody ProductRequest request) {
        return productAdminService.update(productId, request);
    }

    @PostMapping("/{productId}/skus")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDetailResponse addSku(@PathVariable UUID productId, @Valid @RequestBody SkuRequest request) {
        return productAdminService.addSku(productId, request);
    }

    /** Không có DELETE: SKU đã nằm trong đơn hàng/tồn kho của service khác → ngừng bán bằng active=false. */
    @PutMapping("/{productId}/skus/{skuId}")
    public ProductDetailResponse updateSku(@PathVariable UUID productId, @PathVariable UUID skuId,
                                           @Valid @RequestBody SkuRequest request) {
        return productAdminService.updateSku(productId, skuId, request);
    }
}
