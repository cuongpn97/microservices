package com.webshop.search.controller;

import com.webshop.search.dto.request.SearchQuery;
import com.webshop.search.dto.response.ProductSearchResponse;
import com.webshop.search.enums.SearchSort;
import com.webshop.search.service.ProductSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@Validated
@Tag(name = "Search", description = "Tìm kiếm sản phẩm — read model dựng từ CDC, có độ trễ (eventual consistency)")
@RequiredArgsConstructor
public class SearchController {

    private final ProductSearchService productSearchService;

    @GetMapping("/products")
    @Operation(summary = "Tìm sản phẩm (SPU) theo từ khóa/mã SKU (không phân biệt dấu), lọc theo khoảng giá",
            description = "Dữ liệu có thể trễ vài giây so với Product Service; giá ở đây chỉ để hiển thị (checkout luôn đọc "
                    + "lại giá SKU). Lọc giá: SPU có khoảng giá [min, max] giao với khoảng lọc. Không trả tồn kho — "
                    + "lấy từ GET /api/inventory/batch theo skuId khi người dùng mở trang sản phẩm.")
    public ProductSearchResponse products(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @PositiveOrZero Long minPriceVnd,
            @RequestParam(required = false) @PositiveOrZero Long maxPriceVnd,
            @RequestParam(defaultValue = "RELEVANCE") SearchSort sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return productSearchService.search(new SearchQuery(q, minPriceVnd, maxPriceVnd, sort, page, size));
    }
}
