package com.webshop.cart.service;

import com.webshop.cart.dto.response.CartResponse;

import java.util.Map;
import java.util.UUID;

/** Mọi thao tác trên giỏ của MỘT user; userId luôn lấy từ JWT, không bao giờ từ client. */
public interface CartService {

    /**
     * @param withPricing false: chỉ skuId/quantity (Order Service dùng — nó tự lấy giá từ Product Service).
     *                    true: kèm giá hiện tại; Product Service lỗi → vẫn trả giỏ, pricingAvailable=false.
     */
    CartResponse getCart(UUID userId, boolean withPricing);

    /** SKU phải tồn tại và đang bán (hỏi Product Service). Cộng dồn với số đang có. */
    CartResponse addItem(UUID userId, UUID skuId, int quantity);

    CartResponse setQuantity(UUID userId, UUID skuId, int quantity);

    CartResponse removeItem(UUID userId, UUID skuId);

    CartResponse clear(UUID userId);

    /**
     * Trừ các món đã mua khi đơn CONFIRMED. Gọi từ consumer OrderConfirmed, bắt buộc trong transaction cùng Inbox.
     *
     * @param purchased skuId → số lượng đã mua
     */
    void removePurchased(UUID userId, Map<UUID, Integer> purchased);
}
