package com.webshop.payment.entity;

import com.webshop.payment.enums.LedgerEntryType;

import java.time.Instant;
import java.util.UUID;

/** Một bút toán của wallet_ledger (append-only: trigger DB cấm UPDATE/DELETE). */
public record LedgerEntry(
        UUID id,
        LedgerEntryType type,
        long amountVnd,
        long balanceAfterVnd,
        long reservedAfterVnd,
        String referenceId,
        Instant createdAt) {
}
