package com.webshop.product.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.product.dto.request.OptionRequest;
import com.webshop.product.dto.request.ProductRequest;
import com.webshop.product.dto.request.SkuRequest;
import com.webshop.product.dto.response.PageResponse;
import com.webshop.product.dto.response.ProductDetailResponse;
import com.webshop.product.dto.response.ProductSummaryResponse;
import com.webshop.product.entity.Product;
import com.webshop.product.entity.ProductOption;
import com.webshop.product.entity.Specification;
import com.webshop.product.mapper.CatalogMapper;
import com.webshop.product.repository.ProductRepository;
import com.webshop.product.repository.ProductSkuRepository;
import com.webshop.product.service.ProductAdminService;
import com.webshop.product.util.Slugs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // mặc định chỉ đọc; method ghi tự khai báo @Transactional riêng
public class ProductAdminServiceImpl implements ProductAdminService {

    /** Đoạn path cố định dưới /api/products — slug trùng các tên này sẽ bị route nhầm. */
    private static final Set<String> RESERVED_SLUGS = Set.of("manage", "skus", "batch");

    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final ProductWriter productWriter;
    private final CatalogMapper catalogMapper;
    private final Clock clock;

    @Override
    public PageResponse<ProductSummaryResponse> search(String q, Boolean active, int page, int size) {
        String term = q == null ? "" : q.strip().toLowerCase(Locale.ROOT); // "" khớp mọi dòng (like '%%')
        return catalogMapper.toPage(productRepository.search(term, active,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"))));
    }

    @Override
    public ProductDetailResponse get(UUID productId) {
        return productRepository.findById(productId).map(p -> catalogMapper.toDetail(p, false))
                .orElseThrow(() -> ProductWriter.notFound(productId));
    }

    @Override
    @Transactional
    public ProductDetailResponse create(ProductRequest request) {
        UUID id = UUID.randomUUID();
        String slug = request.slug() != null ? requireFreeSlug(request.slug(), id) : generateSlug(request.name(), id);
        Product product = new Product(id, slug, clock.instant());
        try {
            applyDetails(product, request, slug, Boolean.TRUE.equals(request.active()));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("INVALID_CATALOG_CHANGE", e.getMessage());
        }
        return productWriter.create(product);
    }

    @Override
    @Transactional
    public ProductDetailResponse update(UUID productId, ProductRequest request) {
        // Slug bỏ trống → giữ nguyên: đổi slug làm hỏng link đã chia sẻ, chỉ đổi khi admin chủ động nhập.
        String newSlug = request.slug() == null ? null : requireFreeSlug(request.slug(), productId);
        return productWriter.change(productId, product -> applyDetails(product, request,
                newSlug == null ? product.getSlug() : newSlug,
                request.active() == null ? product.isActive() : request.active()));
    }

    @Override
    @Transactional
    public ProductDetailResponse addSku(UUID productId, SkuRequest request) {
        if (productSkuRepository.existsBySkuCode(request.skuCode())) {
            throw skuCodeTaken(request.skuCode());
        }
        return productWriter.change(productId, product -> product.addSku(UUID.randomUUID(), request.skuCode(),
                attributesOf(request), request.priceVnd(), request.compareAtPriceVnd(), request.imageId(),
                request.active() == null || request.active(), clock.instant()));
    }

    @Override
    @Transactional
    public ProductDetailResponse updateSku(UUID productId, UUID skuId, SkuRequest request) {
        if (productSkuRepository.existsBySkuCodeAndIdNot(request.skuCode(), skuId)) {
            throw skuCodeTaken(request.skuCode());
        }
        return productWriter.change(productId, product -> product.updateSku(skuId, request.skuCode(),
                attributesOf(request), request.priceVnd(), request.compareAtPriceVnd(), request.imageId(),
                request.active() == null || request.active(), clock.instant()));
    }

    private void applyDetails(Product product, ProductRequest request, String slug, boolean active) {
        List<Specification> specs = request.specifications() == null ? List.of() : request.specifications().stream()
                .map(s -> new Specification(s.name().strip(), s.value().strip())).toList();
        List<ProductOption> options = request.options() == null ? List.of() : request.options().stream()
                .map(ProductAdminServiceImpl::toOption).toList();
        product.updateDetails(slug, request.name().strip(), blankToNull(request.brand()),
                nullToEmpty(request.shortDescription()), nullToEmpty(request.description()), specs, options, active,
                clock.instant());
    }

    private static ProductOption toOption(OptionRequest o) {
        return new ProductOption(o.name().strip(), o.values().stream().map(String::strip).toList());
    }

    private String requireFreeSlug(String slug, UUID productId) {
        if (RESERVED_SLUGS.contains(slug) || productRepository.existsBySlugAndIdNot(slug, productId)) {
            throw ApiException.conflict("SLUG_TAKEN", "Slug is already used: " + slug);
        }
        return slug;
    }

    /** Slug tự sinh bị trùng → thêm 6 ký tự đầu của id thay vì bắt admin nghĩ slug khác. */
    private String generateSlug(String name, UUID id) {
        String base = Slugs.of(name);
        if (RESERVED_SLUGS.contains(base) || productRepository.existsBySlugAndIdNot(base, id)) {
            return base + "-" + id.toString().substring(0, 6);
        }
        return base;
    }

    private static Map<String, String> attributesOf(SkuRequest request) {
        return request.attributes() == null ? Map.of() : request.attributes();
    }

    private static ApiException skuCodeTaken(String skuCode) {
        return ApiException.conflict("SKU_CODE_TAKEN", "SKU code is already used: " + skuCode);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
