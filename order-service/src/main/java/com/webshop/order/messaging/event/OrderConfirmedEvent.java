package com.webshop.order.messaging.event;

import java.util.List;
import java.util.UUID;

/** Event "OrderConfirmed" v1 — Cart Service nghe để xóa các món đã mua khỏi giỏ. */
public record OrderConfirmedEvent(UUID orderId, UUID userId, long totalVnd, List<Item> items) {

    public record Item(UUID productId, int quantity) {
    }
}
