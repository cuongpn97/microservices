package com.webshop.inventory.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        String status,
        String reason,
        List<ReservationLineResponse> lines,
        Instant updatedAt) {
}
