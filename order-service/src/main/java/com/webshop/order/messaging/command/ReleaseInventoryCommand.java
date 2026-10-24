package com.webshop.order.messaging.command;

import java.util.UUID;

/** Command "ReleaseInventory" v1 (bù trừ): trả hàng đang giữ. reason = failureReason của Saga. */
public record ReleaseInventoryCommand(UUID reservationId, String reason) {
}
