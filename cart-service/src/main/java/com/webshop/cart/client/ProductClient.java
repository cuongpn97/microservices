package com.webshop.cart.client;

import com.webshop.cart.client.dto.SkuInfo;
import com.webshop.common.http.ResilientCaller;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Client đồng bộ tới Product Service. GET là idempotent → được retry có giới hạn + circuit breaker "product-service".
 * Việc gọi HTTP do {@link CatalogApi} (HTTP Interface) đảm nhiệm; lớp này giữ resilience và ngữ nghĩa nghiệp vụ (404 → empty).
 */
@Component
@RequiredArgsConstructor
public class ProductClient {

    private static final String DEPENDENCY = "product-service";

    private final CatalogApi api;
    private final ResilientCaller resilience;

    public Optional<SkuInfo> findSku(UUID skuId) {
        return resilience.idempotent(DEPENDENCY, () -> {
            try {
                return Optional.ofNullable(api.findSku(skuId));
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty();
            }
        });
    }

    public List<SkuInfo> findSkus(Collection<UUID> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        return resilience.idempotent(DEPENDENCY, () -> api.findSkus(skuIds));
    }
}
