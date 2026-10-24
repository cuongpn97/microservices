package com.webshop.payment.dto.response;

import java.time.Instant;
import java.util.UUID;

/** referenceId: orderId (với RESERVE/CAPTURE/RELEASE) hoặc userId (với INITIAL_CREDIT). */
public record LedgerEntryResponse(
        UUID id,
        String type,
        long amountVnd,
        long balanceAfterVnd,
        long reservedAfterVnd,
        String referenceId,
        Instant createdAt) {
}
