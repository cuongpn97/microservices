package com.webshop.product.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.product.dto.response.ProductDetailResponse;
import com.webshop.product.entity.Product;
import com.webshop.product.mapper.CatalogMapper;
import com.webshop.product.projection.CatalogProjection;
import com.webshop.product.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Khuôn chung cho MỌI thay đổi aggregate Product (dùng bởi service admin và service ảnh):
 * khóa dòng SPU → áp dụng thay đổi → flush → ghi catalog_document → trả chi tiết mới.
 * Gom về một chỗ để không đường ghi nào "quên" cập nhật document cho search.
 */
@Component
@RequiredArgsConstructor
public class ProductWriter {

    private final ProductRepository productRepository;
    private final EntityManager entityManager;
    private final CatalogProjection catalogProjection;
    private final CatalogMapper catalogMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductDetailResponse change(UUID productId, Consumer<Product> change) {
        Product product = productRepository.lockById(productId).orElseThrow(() -> notFound(productId));
        try {
            change.accept(product);
        } catch (IllegalArgumentException e) {
            // Product từ chối (thuộc tính SKU sai, trùng tổ hợp, quá số ảnh...) → 422 kèm lý do.
            throw ApiException.unprocessable("INVALID_CATALOG_CHANGE", e.getMessage());
        }
        return flushAndPublish(product);
    }

    /**
     * Lưu SPU MỚI. Dùng persist thay vì repository.save: id do ứng dụng sinh (không null) nên Spring Data coi entity
     * là "đã tồn tại" và gọi merge — tạo bản sao managed, còn object đang cầm vẫn detached.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductDetailResponse create(Product product) {
        entityManager.persist(product);
        return flushAndPublish(product);
    }

    private ProductDetailResponse flushAndPublish(Product product) {
        try {
            entityManager.flush();
        } catch (PersistenceException | DataIntegrityViolationException e) {
            throw uniqueViolation(e);
        }
        catalogProjection.refresh(product);
        return catalogMapper.toDetail(product, false);
    }

    static ApiException notFound(UUID productId) {
        return ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found: " + productId);
    }

    /** Unique index là chốt chặn cuối khi hai request song song cùng vượt qua bước kiểm tra trước đó. */
    private static RuntimeException uniqueViolation(RuntimeException e) {
        String detail = String.valueOf(rootCause(e).getMessage());
        if (detail.contains("ux_product_sku_code")) {
            return ApiException.conflict("SKU_CODE_TAKEN", "SKU code is already used by another SKU");
        }
        if (detail.contains("ux_product_slug")) {
            return ApiException.conflict("SLUG_TAKEN", "Slug is already used by another product");
        }
        if (detail.contains("ux_product_sku_attributes")) {
            return ApiException.conflict("DUPLICATE_VARIANT", "Another SKU already has these attributes");
        }
        return e;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
