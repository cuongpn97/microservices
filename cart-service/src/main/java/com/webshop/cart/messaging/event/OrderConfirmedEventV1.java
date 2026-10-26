package com.webshop.cart.messaging.event;

import java.util.List;
import java.util.UUID;

/**
 * OrderConfirmed v1 (trước khi có SKU): dòng hàng theo productId. Vẫn nhận để message phát TRƯỚC lúc nâng cấp (còn
 * nằm trong queue/retry/DLQ) không bị đẩy vào DLQ vì "version không hỗ trợ". productId cũ chính là id của SKU mặc
 * định (product-service V4 giữ nguyên id) → nâng cấp (upcast) sang v2 chỉ là đổi tên field.
 */
public record OrderConfirmedEventV1(UUID orderId, UUID userId, List<Item> items) {

    public record Item(UUID productId, int quantity) {
    }

    public OrderConfirmedEvent upcast() {
        return new OrderConfirmedEvent(orderId, userId,
                items.stream().map(i -> new OrderConfirmedEvent.Item(i.productId(), i.quantity())).toList());
    }
}
