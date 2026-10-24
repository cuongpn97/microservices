package com.webshop.cart.client;

import com.webshop.cart.client.dto.ProductInfo;
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
 * Việc gọi HTTP do {@link ProductApi} (HTTP Interface) đảm nhiệm; lớp này giữ resilience và ngữ nghĩa nghiệp vụ (404 → empty).
 */
@Component
@RequiredArgsConstructor
public class ProductClient {

    private static final String DEPENDENCY = "product-service";

    private final ProductApi api;
    private final ResilientCaller resilience;

    public Optional<ProductInfo> find(UUID productId) {
        return resilience.idempotent(DEPENDENCY, () -> {
            try {
                return Optional.ofNullable(api.find(productId));
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty();
            }
        });
    }

    public List<ProductInfo> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return resilience.idempotent(DEPENDENCY, () -> api.findAll(ids));
    }
}
