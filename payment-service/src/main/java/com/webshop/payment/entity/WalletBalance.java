package com.webshop.payment.entity;

/** Số dư của ví ngay sau một câu UPDATE ... RETURNING — dùng để ghi balance_after/reserved_after vào ledger. */
public record WalletBalance(long balanceVnd, long reservedVnd) {
}
