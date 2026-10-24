package com.webshop.payment.enums;

/** Loại bút toán trong wallet_ledger (khớp CHECK constraint của entry_type). */
public enum LedgerEntryType {
    INITIAL_CREDIT,
    RESERVE,
    CAPTURE,
    RELEASE
}
