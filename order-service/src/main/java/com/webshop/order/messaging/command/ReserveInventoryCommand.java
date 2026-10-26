package com.webshop.order.messaging.command;

import java.util.List;
import java.util.UUID;

/**
 * Command "ReserveInventory" v2 gửi Inventory Service (v2: dòng hàng theo skuId — tồn kho tính theo biến thể).
 * Các payload command trong package này là contract với Inventory/Payment: tên field phải khớp với record nhận bên
 * đó (ví dụ inventory ReserveInventoryCommand).
 */
public record ReserveInventoryCommand(UUID reservationId, List<Item> items) {

    public static final int VERSION = 2;

    public record Item(UUID skuId, int quantity) {
    }
}
