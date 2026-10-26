package com.webshop.review.messaging.event;

import java.util.List;
import java.util.UUID;

/**
 * Payload event OrderConfirmed v2 do Order Service phát. Review chỉ cần biết ai mua SPU nào (và biến thể nào);
 * totalVnd, quantity... bị bỏ qua (tolerant reader).
 */
public record OrderConfirmedEvent(UUID orderId, UUID userId, List<Item> items) {

    /** @param productId SPU — có từ v2; v1 không có nên không ghi nhận được lượt mua. */
    public record Item(UUID skuId, UUID productId, String variantName) {
    }
}
