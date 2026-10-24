package com.webshop.order.messaging.command;

import java.util.UUID;

/** Command "CommitInventory" v1: hàng đã giữ chính thức rời kho. */
public record CommitInventoryCommand(UUID reservationId) {
}
