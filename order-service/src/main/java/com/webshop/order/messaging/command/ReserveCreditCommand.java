package com.webshop.order.messaging.command;

import java.util.UUID;

/** Command "ReserveCredit" v1 gửi Payment Service: giữ amountVnd trong ví của userId. */
public record ReserveCreditCommand(UUID reservationId, UUID userId, long amountVnd) {
}
