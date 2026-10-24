package com.webshop.order.messaging.command;

import java.util.List;
import java.util.UUID;

/**
 * Command "ReserveInventory" v1 gửi Inventory Service. Các payload command trong package này là contract với
 * Inventory/Payment: tên field phải khớp với record nhận bên đó (ví dụ inventory ReserveInventoryCommand).
 */
public record ReserveInventoryCommand(UUID reservationId, List<Item> items) {

    public record Item(UUID productId, int quantity) {
    }
}
