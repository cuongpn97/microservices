package com.webshop.product.service.impl;

import com.webshop.product.projection.CatalogProjection;
import com.webshop.product.repository.ProductRepository;
import com.webshop.product.service.ProductRatingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductRatingServiceImpl implements ProductRatingService {

    private final ProductRepository productRepository;
    private final CatalogProjection catalogProjection;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyRating(UUID productId, int ratingCount, long ratingSum, long version) {
        productRepository.lockById(productId).ifPresentOrElse(product -> {
            if (product.applyRating(ratingCount, ratingSum, version)) {
                productRepository.flush();
                catalogProjection.refresh(product);
            } else {
                log.info("Stale rating for product {} ignored (version {} <= {})", productId, version,
                        product.getRatingVersion());
            }
        }, () -> log.warn("Rating for unknown product {} ignored", productId));
    }
}
