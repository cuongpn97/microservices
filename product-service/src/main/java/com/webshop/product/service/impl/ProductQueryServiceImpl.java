package com.webshop.product.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.product.dto.response.PageResponse;
import com.webshop.product.dto.response.ProductDetailResponse;
import com.webshop.product.dto.response.ProductSummaryResponse;
import com.webshop.product.dto.response.SkuInfoResponse;
import com.webshop.product.entity.Product;
import com.webshop.product.mapper.CatalogMapper;
import com.webshop.product.repository.ProductRepository;
import com.webshop.product.repository.ProductSkuRepository;
import com.webshop.product.service.ProductQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
// readOnly transaction giữ session mở khi mapper đọc skus/images (lazy, nạp theo lô nhờ default_batch_fetch_size).
@Transactional(readOnly = true)
public class ProductQueryServiceImpl implements ProductQueryService {

    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final CatalogMapper catalogMapper;

    @Override
    public PageResponse<ProductSummaryResponse> listActive(int page, int size) {
        return catalogMapper.toPage(productRepository.findByActiveTrue(PageRequest.of(page, size, Sort.by("name"))));
    }

    @Override
    public ProductDetailResponse getPublished(String idOrSlug) {
        return findByIdOrSlug(idOrSlug)
                .filter(Product::isActive)
                .map(p -> catalogMapper.toDetail(p, true))
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found: " + idOrSlug));
    }

    @Override
    public SkuInfoResponse getSku(UUID skuId) {
        return productSkuRepository.findAllWithProduct(List.of(skuId)).stream().findFirst()
                .map(catalogMapper::toSkuInfo)
                .orElseThrow(() -> ApiException.notFound("SKU_NOT_FOUND", "SKU not found: " + skuId));
    }

    @Override
    public List<SkuInfoResponse> getSkus(List<UUID> skuIds) {
        return productSkuRepository.findAllWithProduct(skuIds).stream().map(catalogMapper::toSkuInfo).toList();
    }

    private Optional<Product> findByIdOrSlug(String idOrSlug) {
        try {
            return productRepository.findById(UUID.fromString(idOrSlug));
        } catch (IllegalArgumentException notUuid) {
            return productRepository.findBySlug(idOrSlug);
        }
    }
}
