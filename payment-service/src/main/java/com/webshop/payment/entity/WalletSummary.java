package com.webshop.payment.entity;

import java.time.Instant;
import java.util.UUID;

/** Ví kèm tổng tiền đã capture (tính từ ledger) — kết quả của một câu SELECT, chỉ để đọc. */
public record WalletSummary(
        UUID userId,
        long balanceVnd,
        long reservedVnd,
        long totalCapturedVnd,
        Instant updatedAt) {

    /** Số tiền còn dùng được = balance - reserved. */
    public long availableVnd() {
        return balanceVnd - reservedVnd;
    }
}
