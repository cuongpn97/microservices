package com.webshop.cart.messaging.event;

import java.util.List;
import java.util.UUID;

/**
 * Payload event OrderConfirmed v2 do Order Service phát (v2: dòng hàng theo skuId). Order còn gửi totalVnd,
 * productId, variantName nhưng Cart không cần → không khai báo (tolerant reader).
 */
public record OrderConfirmedEvent(UUID orderId, UUID userId, List<Item> items) {

    public record Item(UUID skuId, int quantity) {
    }
}
