package com.webshop.cart.application;

import com.webshop.cart.client.ProductClient;
import com.webshop.cart.domain.Cart;
import com.webshop.cart.domain.CartRepository;
import com.webshop.common.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class CartService {

    private final CartRepository carts;
    private final ProductClient products;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CartService(CartRepository carts, ProductClient products, TransactionTemplate tx, Clock clock) {
        this.carts = carts;
        this.products = products;
        this.tx = tx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Cart get(UUID userId) {
        return carts.findById(userId).orElseGet(() -> new Cart(userId, clock.instant()));
    }

    public Cart addItem(UUID userId, UUID productId, int quantity) {
        // Gọi HTTP NGOÀI transaction DB: không giữ connection/khóa trong lúc chờ mạng.
        requireActiveProduct(productId);
        return modify(userId, cart -> cart.add(productId, quantity, clock.instant()));
    }

    public Cart setQuantity(UUID userId, UUID productId, int quantity) {
        requireActiveProduct(productId);
        return modify(userId, cart -> cart.set(productId, quantity, clock.instant()));
    }

    public Cart removeItem(UUID userId, UUID productId) {
        return modify(userId, cart -> cart.remove(productId, clock.instant()));
    }

    public Cart clear(UUID userId) {
        return modify(userId, cart -> cart.clear(clock.instant()));
    }

    /** Gọi từ consumer OrderConfirmed, trong transaction cùng Inbox. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removePurchased(UUID userId, Map<UUID, Integer> purchased) {
        carts.lockByUserId(userId).ifPresent(cart -> cart.removePurchased(purchased, clock.instant()));
    }

    private Cart modify(UUID userId, Consumer<Cart> change) {
        try {
            return tx.execute(status -> {
                carts.createIfAbsent(userId, clock.instant());
                Cart cart = carts.lockByUserId(userId).orElseThrow();
                change.accept(cart);
                return carts.saveAndFlush(cart);
            });
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CART_OPERATION", e.getMessage());
        }
    }

    private void requireActiveProduct(UUID productId) {
        ProductClient.ProductInfo product = products.find(productId)
                .orElseThrow(() -> ApiException.unprocessable("PRODUCT_NOT_FOUND", "Product not found: " + productId));
        if (!product.active()) {
            throw ApiException.unprocessable("PRODUCT_UNAVAILABLE", "Product is not available: " + productId);
        }
    }
}
