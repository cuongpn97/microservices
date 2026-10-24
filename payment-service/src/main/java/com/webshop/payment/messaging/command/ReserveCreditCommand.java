package com.webshop.payment.messaging.command;

import java.util.UUID;

/** Payload command "ReserveCredit" v1 do Saga của Order Service gửi. reservationId = orderId. */
public record ReserveCreditCommand(UUID reservationId, UUID userId, long amountVnd) {
}
