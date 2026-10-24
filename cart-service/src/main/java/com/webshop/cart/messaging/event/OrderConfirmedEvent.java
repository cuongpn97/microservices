package com.webshop.cart.messaging.event;

import java.util.List;
import java.util.UUID;

/**
 * Payload event OrderConfirmed v1 do Order Service phát. Order còn gửi totalVnd nhưng Cart không cần → không khai báo.
 */
public record OrderConfirmedEvent(UUID orderId, UUID userId, List<Item> items) {

    public record Item(UUID productId, int quantity) {
    }
}
