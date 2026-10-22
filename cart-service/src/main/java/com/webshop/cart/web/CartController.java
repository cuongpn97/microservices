package com.webshop.cart.web;

import com.webshop.cart.application.CartService;
import com.webshop.cart.client.ProductClient;
import com.webshop.cart.domain.Cart;
import com.webshop.common.http.DependencyUnavailableException;
import com.webshop.common.security.WebshopJwt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mọi endpoint thao tác trên giỏ của user trong JWT ("sub"). Không có endpoint nào nhận userId từ client.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private static final Logger log = LoggerFactory.getLogger(CartController.class);

    private final CartService carts;
    private final ProductClient products;

    public CartController(CartService carts, ProductClient products) {
        this.carts = carts;
        this.products = products;
    }

    /**
     * @param pricing false: chỉ trả productId/quantity (Order Service dùng — nó tự lấy giá từ Product Service).
     */
    @GetMapping
    public CartResponse get(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "true") boolean pricing) {
        Cart cart = carts.get(WebshopJwt.userId(jwt));
        return pricing ? withPricing(cart) : CartResponse.withoutPricing(cart, false);
    }

    @PostMapping("/items")
    public CartResponse add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddItemRequest request) {
        return CartResponse.withoutPricing(
                carts.addItem(WebshopJwt.userId(jwt), request.productId(), request.quantity()), false);
    }

    @PutMapping("/items/{productId}")
    public CartResponse set(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                            @Valid @RequestBody SetQuantityRequest request) {
        return CartResponse.withoutPricing(carts.setQuantity(WebshopJwt.userId(jwt), productId, request.quantity()),
                false);
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return CartResponse.withoutPricing(carts.removeItem(WebshopJwt.userId(jwt), productId), false);
    }

    @DeleteMapping
    public CartResponse clear(@AuthenticationPrincipal Jwt jwt) {
        return CartResponse.withoutPricing(carts.clear(WebshopJwt.userId(jwt)), false);
    }

    /**
     * Giá hiển thị là giá HIỆN TẠI của Product Service (chỉ tham khảo); giá chốt là giá Order Service lấy lúc checkout.
     * Fallback: Product Service lỗi/circuit OPEN → vẫn trả giỏ, chỉ không có giá (pricingAvailable=false).
     */
    private CartResponse withPricing(Cart cart) {
        if (cart.getItems().isEmpty()) {
            return CartResponse.withoutPricing(cart, true);
        }
        try {
            Map<UUID, ProductClient.ProductInfo> info = products.findAll(cart.getItems().keySet()).stream()
                    .collect(Collectors.toMap(ProductClient.ProductInfo::id, Function.identity()));
            List<CartItemResponse> items = cart.getItems().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> {
                        ProductClient.ProductInfo p = info.get(e.getKey());
                        return p == null
                                ? new CartItemResponse(e.getKey(), e.getValue(), null, null, null, false)
                                : new CartItemResponse(e.getKey(), e.getValue(), p.name(), p.priceVnd(),
                                Math.multiplyExact(p.priceVnd(), (long) e.getValue()), p.active());
                    })
                    .toList();
            long total = items.stream().filter(i -> i.lineTotalVnd() != null && i.active())
                    .mapToLong(CartItemResponse::lineTotalVnd).sum();
            return new CartResponse(cart.getUserId(), cart.getRevision(), items, total, true, cart.getUpdatedAt());
        } catch (DependencyUnavailableException e) {
            log.warn("Product pricing unavailable, returning cart without prices: {}", e.getMessage());
            return CartResponse.withoutPricing(cart, false);
        }
    }

    public record AddItemRequest(@NotNull UUID productId, @NotNull @Positive @Max(99) Integer quantity) {
    }

    public record SetQuantityRequest(@NotNull @Positive @Max(99) Integer quantity) {
    }

    public record CartItemResponse(UUID productId, int quantity, String name, Long unitPriceVnd, Long lineTotalVnd,
                                   boolean active) {
    }

    public record CartResponse(UUID userId, long revision, List<CartItemResponse> items, Long estimatedTotalVnd,
                               boolean pricingAvailable, Instant updatedAt) {
        static CartResponse withoutPricing(Cart cart, boolean pricingAvailable) {
            List<CartItemResponse> items = cart.getItems().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .map(e -> new CartItemResponse(e.getKey(), e.getValue(), null, null, null, true))
                    .toList();
            return new CartResponse(cart.getUserId(), cart.getRevision(), items,
                    pricingAvailable && items.isEmpty() ? 0L : null, pricingAvailable, cart.getUpdatedAt());
        }
    }
}
