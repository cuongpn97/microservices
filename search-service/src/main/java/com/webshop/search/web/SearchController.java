package com.webshop.search.web;

import com.webshop.search.query.ProductSearchService;
import com.webshop.search.query.SearchQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@Validated
@Tag(name = "Search", description = "Tìm kiếm sản phẩm — read model dựng từ CDC, có độ trễ (eventual consistency)")
public class SearchController {

    private final ProductSearchService search;

    public SearchController(ProductSearchService search) {
        this.search = search;
    }

    @GetMapping("/products")
    @Operation(summary = "Tìm sản phẩm theo từ khóa (không phân biệt dấu), lọc theo khoảng giá",
            description = "Dữ liệu có thể trễ vài giây so với Product Service; giá ở đây chỉ để hiển thị (checkout luôn đọc "
                    + "lại giá). Không trả tồn kho — lấy từ GET /api/inventory/batch cho các sản phẩm đang hiển thị.")
    public ProductSearchService.SearchResult products(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @PositiveOrZero Long minPriceVnd,
            @RequestParam(required = false) @PositiveOrZero Long maxPriceVnd,
            @RequestParam(defaultValue = "RELEVANCE") SearchQuery.Sort sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return search.search(new SearchQuery(q, minPriceVnd, maxPriceVnd, sort, page, size));
    }
}
