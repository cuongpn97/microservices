package com.webshop.product;

import com.webshop.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@Validated
public class ProductController {

    private final ProductRepository products;
    private final Clock clock;

    public ProductController(ProductRepository products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    /** Danh sách công khai: chỉ sản phẩm đang bán. */
    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse list(@RequestParam(defaultValue = "0") @Min(0) int page,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<Product> result = products.findByActiveTrue(PageRequest.of(page, size, Sort.by("name")));
        return new PageResponse(result.map(ProductResponse::from).getContent(), page, size,
                result.getTotalElements());
    }

    /** Tra cứu theo id: trả cả sản phẩm inactive (kèm cờ active) để Cart/Order tự kiểm tra trạng thái. */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ProductResponse get(@PathVariable UUID id) {
        return products.findById(id).map(ProductResponse::from)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found: " + id));
    }

    /** Tra cứu nhiều sản phẩm một lần (Order Service dùng khi checkout) — tránh N lời gọi HTTP. */
    @GetMapping("/batch")
    @Transactional(readOnly = true)
    public List<ProductResponse> batch(@RequestParam @Size(min = 1, max = 100) List<UUID> ids) {
        return products.findAllById(ids).stream().map(ProductResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductResponse create(@Valid @RequestBody ProductRequest request) {
        Instant now = clock.instant();
        Product product = products.save(new Product(UUID.randomUUID(), request.name(),
                request.description() == null ? "" : request.description(), request.priceVnd(),
                request.active() == null || request.active(), now));
        return ProductResponse.from(product);
    }

    /**
     * Đổi giá chỉ ảnh hưởng đơn hàng TƯƠNG LAI: đơn đã checkout giữ snapshot giá tại thời điểm checkout.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        Product product = products.findById(id)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found: " + id));
        product.update(request.name(), request.description() == null ? "" : request.description(),
                request.priceVnd(), request.active() == null || request.active(), clock.instant());
        return ProductResponse.from(products.saveAndFlush(product));
    }

    public record ProductRequest(@NotBlank @Size(max = 200) String name,
                                 @Size(max = 2000) String description,
                                 @NotNull @PositiveOrZero Long priceVnd,
                                 Boolean active) {
    }

    public record ProductResponse(UUID id, String name, String description, long priceVnd, boolean active,
                                  Instant updatedAt) {
        static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getDescription(), p.getPriceVnd(), p.isActive(),
                    p.getUpdatedAt());
        }
    }

    public record PageResponse(List<ProductResponse> items, int page, int size, long totalElements) {
    }
}
