package com.webshop.order.messaging.event;

import java.util.UUID;

/**
 * Phần payload chung của mọi reply từ Inventory (InventoryReplyEvent) và Payment (CreditReplyEvent).
 * Loại reply (InventoryReserved, CreditCaptured...) nằm ở eventType của envelope.
 *
 * @param reservationId = orderId = sagaId.
 */
public record SagaReplyEvent(UUID reservationId, String status, String reason) {
}
