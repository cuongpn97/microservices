package com.webshop.inventory.messaging.event;

import java.util.UUID;

/**
 * Payload reply gửi lại Saga (routing key inventory.reply). Loại reply (InventoryReserved, InventoryCommitted...)
 * nằm ở eventType của envelope, không nằm trong payload.
 */
public record InventoryReplyEvent(UUID reservationId, String status, String reason) {
}
