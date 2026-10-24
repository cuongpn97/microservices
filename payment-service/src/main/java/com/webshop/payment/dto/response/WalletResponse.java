package com.webshop.payment.dto.response;

import java.time.Instant;
import java.util.UUID;

/** availableVnd = balanceVnd - reservedVnd: số tiền còn dùng được để đặt đơn mới. */
public record WalletResponse(
        UUID userId,
        long balanceVnd,
        long reservedVnd,
        long availableVnd,
        long totalCapturedVnd,
        Instant updatedAt) {
}
