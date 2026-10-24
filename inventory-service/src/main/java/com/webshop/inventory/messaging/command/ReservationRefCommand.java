package com.webshop.inventory.messaging.command;

import java.util.UUID;

/**
 * Payload chung của command "CommitInventory" và "ReleaseInventory" v1: chỉ cần biết reservation nào.
 * reason chỉ có ý nghĩa với Release (ví dụ PAYMENT_FAILED, SAGA_TIMEOUT).
 */
public record ReservationRefCommand(UUID reservationId, String reason) {
}
