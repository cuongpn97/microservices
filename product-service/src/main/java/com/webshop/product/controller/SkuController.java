package com.webshop.product.controller;

import com.webshop.product.dto.response.SkuInfoResponse;
import com.webshop.product.service.ProductQueryService;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tra cứu SKU — đơn vị mà Cart/Order/Inventory dùng. Trả cả SKU ngừng bán (active=false) để bên gọi tự kiểm tra.
 * Công khai như catalog (giá và tên không phải bí mật).
 */
@RestController
@RequestMapping("/api/products/skus")
@Validated
@RequiredArgsConstructor
public class SkuController {

    private final ProductQueryService productQueryService;

    @GetMapping("/{skuId}")
    public SkuInfoResponse get(@PathVariable UUID skuId) {
        return productQueryService.getSku(skuId);
    }

    @GetMapping("/batch")
    public List<SkuInfoResponse> batch(@RequestParam @Size(min = 1, max = 100) List<UUID> ids) {
        return productQueryService.getSkus(ids);
    }
}
