package com.webshop.inventory.messaging.command;

import java.util.List;
import java.util.UUID;

/**
 * "ReserveInventory" v1 (trước khi có SKU): dòng hàng theo productId. Vẫn nhận để command phát TRƯỚC lúc nâng cấp
 * (còn trong queue, retry queue hoặc được redrive từ DLQ) không bị từ chối. productId cũ chính là id SKU mặc định
 * (product-service V4 giữ nguyên id) và inventory_item đã đổi cột sang sku_id với cùng giá trị → chỉ cần đổi tên field.
 */
public record ReserveInventoryCommandV1(UUID reservationId, List<Item> items) {

    public record Item(UUID productId, int quantity) {
    }

    public ReserveInventoryCommand upcast() {
        return new ReserveInventoryCommand(reservationId,
                items.stream().map(i -> new ReserveInventoryCommand.Item(i.productId(), i.quantity())).toList());
    }
}
