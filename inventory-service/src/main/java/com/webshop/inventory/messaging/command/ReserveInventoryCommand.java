package com.webshop.inventory.messaging.command;

import java.util.List;
import java.util.UUID;

/**
 * Payload command "ReserveInventory" v1 do Saga của Order Service gửi. reservationId = orderId.
 * Contract giữa các service: phải khớp với bản Order Service gửi đi.
 */
public record ReserveInventoryCommand(UUID reservationId, List<Item> items) {

    public record Item(UUID productId, int quantity) {
    }
}
