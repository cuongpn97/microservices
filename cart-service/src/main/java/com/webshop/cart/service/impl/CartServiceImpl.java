package com.webshop.cart.service.impl;

import com.webshop.cart.client.ProductClient;
import com.webshop.cart.client.dto.ProductInfo;
import com.webshop.cart.dto.response.CartResponse;
import com.webshop.cart.entity.Cart;
import com.webshop.cart.mapper.CartMapper;
import com.webshop.cart.repository.CartRepository;
import com.webshop.cart.service.CartService;
import com.webshop.common.http.DependencyUnavailableException;
import com.webshop.common.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Nguyên tắc chung: mọi lời gọi HTTP sang Product Service nằm NGOÀI transaction DB — không giữ connection/khóa
 * trong lúc chờ mạng. Vì vậy các method ở đây KHÔNG gắn @Transactional; phần ghi DB dùng TransactionTemplate
 * để mở transaction đúng đoạn cần.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final ProductClient productClient;
    private final CartMapper cartMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public CartResponse getCart(UUID userId, boolean withPricing) {
        // Chưa có giỏ → giỏ rỗng tạm (không lưu). findById tự chạy trong transaction riêng của Spring Data.
        Cart cart = cartRepository.findById(userId).orElseGet(() -> new Cart(userId, clock.instant()));
        if (!withPricing) {
            return cartMapper.toResponseWithoutPricing(cart, false);
        }
        if (cart.getItems().isEmpty()) {
            return cartMapper.toResponseWithoutPricing(cart, true);
        }
        try {
            Map<UUID, ProductInfo> products = productClient.findAll(cart.getItems().keySet()).stream()
                    .collect(Collectors.toMap(ProductInfo::id, Function.identity()));
            return cartMapper.toResponseWithPricing(cart, products);
        } catch (DependencyUnavailableException e) {
            // Fallback: Product Service lỗi/circuit OPEN → vẫn trả giỏ, chỉ không có giá.
            log.warn("Product pricing unavailable, returning cart without prices: {}", e.getMessage());
            return cartMapper.toResponseWithoutPricing(cart, false);
        }
    }

    @Override
    public CartResponse addItem(UUID userId, UUID productId, int quantity) {
        requireActiveProduct(productId);
        return modify(userId, cart -> cart.add(productId, quantity, clock.instant()));
    }

    @Override
    public CartResponse setQuantity(UUID userId, UUID productId, int quantity) {
        requireActiveProduct(productId);
        return modify(userId, cart -> cart.set(productId, quantity, clock.instant()));
    }

    @Override
    public CartResponse removeItem(UUID userId, UUID productId) {
        return modify(userId, cart -> cart.remove(productId, clock.instant()));
    }

    @Override
    public CartResponse clear(UUID userId) {
        return modify(userId, cart -> cart.clear(clock.instant()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void removePurchased(UUID userId, Map<UUID, Integer> purchased) {
        cartRepository.lockByUserId(userId).ifPresent(cart -> cart.removePurchased(purchased, clock.instant()));
    }

    /** Tạo giỏ nếu chưa có → khóa dòng → áp dụng thay đổi → lưu, trong MỘT transaction. Trả giỏ không kèm giá. */
    private CartResponse modify(UUID userId, Consumer<Cart> change) {
        try {
            Cart saved = transactionTemplate.execute(status -> {
                cartRepository.createIfAbsent(userId, clock.instant());
                Cart cart = cartRepository.lockByUserId(userId).orElseThrow();
                change.accept(cart);
                return cartRepository.saveAndFlush(cart);
            });
            return cartMapper.toResponseWithoutPricing(saved, false);
        } catch (IllegalArgumentException e) {
            // Cart từ chối (quá 99 cái, quá 50 sản phẩm...) → 400.
            throw ApiException.badRequest("INVALID_CART_OPERATION", e.getMessage());
        }
    }

    private void requireActiveProduct(UUID productId) {
        ProductInfo product = productClient.find(productId)
                .orElseThrow(() -> ApiException.unprocessable("PRODUCT_NOT_FOUND", "Product not found: " + productId));
        if (!product.active()) {
            throw ApiException.unprocessable("PRODUCT_UNAVAILABLE", "Product is not available: " + productId);
        }
    }
}
