package com.webshop.product.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.product.dto.request.ProductRequest;
import com.webshop.product.dto.response.PageResponse;
import com.webshop.product.dto.response.ProductResponse;
import com.webshop.product.entity.Product;
import com.webshop.product.mapper.ProductMapper;
import com.webshop.product.repository.ProductRepository;
import com.webshop.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor // sinh constructor cho các field final → Spring inject qua constructor
@Transactional(readOnly = true) // mặc định chỉ đọc; method ghi tự khai báo @Transactional riêng
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;
    private final Clock clock;

    @Override
    public PageResponse<ProductResponse> listActive(int page, int size) {
        return productMapper.toPageResponse(
                productRepository.findByActiveTrue(PageRequest.of(page, size, Sort.by("name"))));
    }

    @Override
    public ProductResponse getById(UUID id) {
        return productMapper.toResponse(findOrThrow(id));
    }

    @Override
    public List<ProductResponse> getByIds(List<UUID> ids) {
        return productMapper.toResponses(productRepository.findAllById(ids));
    }

    @Override
    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(UUID.randomUUID(), request.name(), descriptionOf(request),
                request.priceVnd(), activeOf(request), clock.instant());
        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    @Transactional
    public ProductResponse update(UUID id, ProductRequest request) {
        Product product = findOrThrow(id);
        product.update(request.name(), descriptionOf(request), request.priceVnd(), activeOf(request),
                clock.instant());
        // saveAndFlush để @Version tăng ngay → updatedAt/version trong response là giá trị đã ghi DB
        return productMapper.toResponse(productRepository.saveAndFlush(product));
    }

    private Product findOrThrow(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found: " + id));
    }

    private static String descriptionOf(ProductRequest request) {
        return request.description() == null ? "" : request.description();
    }

    private static boolean activeOf(ProductRequest request) {
        return request.active() == null || request.active();
    }
}
