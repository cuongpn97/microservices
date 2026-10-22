package com.webshop.order.client;

import com.webshop.common.http.ResilientCaller;
import com.webshop.common.http.RestClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Đọc giỏ hàng của user bằng cách CHUYỂN TIẾP access token của chính user đó.
 * Cart Service tự verify token và chỉ trả giỏ của "sub" trong token → Order Service không thể (và không cần)
 * truyền userId tùy ý. GET idempotent → retry có giới hạn + circuit breaker "cart-service".
 */
@Component
public class CartClient {

    private static final String DEPENDENCY = "cart-service";

    private final RestClient client;
    private final ResilientCaller resilience;

    public CartClient(RestClientFactory factory, ResilientCaller resilience,
                      @Value("${webshop.services.cart-url}") String cartUrl) {
        this.client = factory.create(cartUrl);
        this.resilience = resilience;
    }

    public CartSnapshot getCart(String bearerToken) {
        return resilience.idempotent(DEPENDENCY, () -> client.get()
                .uri("/api/cart?pricing=false")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .body(CartSnapshot.class));
    }

    public record CartSnapshot(UUID userId, long revision, List<Line> items) {
        public record Line(UUID productId, int quantity) {
        }
    }
}
