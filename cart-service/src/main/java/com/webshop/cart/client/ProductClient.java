package com.webshop.cart.client;

import com.webshop.common.http.ResilientCaller;
import com.webshop.common.http.RestClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Client đồng bộ tới Product Service. GET là idempotent → được retry có giới hạn + circuit breaker "product-service".
 */
@Component
public class ProductClient {

    private static final String DEPENDENCY = "product-service";

    private final RestClient client;
    private final ResilientCaller resilience;

    public ProductClient(RestClientFactory factory, ResilientCaller resilience,
                         @Value("${webshop.services.product-url}") String productUrl) {
        this.client = factory.create(productUrl);
        this.resilience = resilience;
    }

    public Optional<ProductInfo> find(UUID productId) {
        return resilience.idempotent(DEPENDENCY, () -> {
            try {
                return Optional.ofNullable(client.get().uri("/api/products/{id}", productId)
                        .retrieve().body(ProductInfo.class));
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty();
            }
        });
    }

    public List<ProductInfo> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return resilience.idempotent(DEPENDENCY, () -> client.get()
                .uri(b -> b.path("/api/products/batch").queryParam("ids", ids.toArray()).build())
                .retrieve().body(new ParameterizedTypeReference<List<ProductInfo>>() {
                }));
    }

    public record ProductInfo(UUID id, String name, long priceVnd, boolean active) {
    }
}
