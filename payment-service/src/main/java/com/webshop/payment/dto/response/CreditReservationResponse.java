package com.webshop.payment.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CreditReservationResponse(
        UUID reservationId,
        UUID userId,
        long amountVnd,
        String status,
        String reason,
        Instant updatedAt) {
}
