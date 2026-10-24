package com.webshop.payment.enums;

/**
 * Trạng thái giữ tiền cho một đơn (khớp CHECK constraint của credit_reservation.status):
 * <pre>
 *   (none) ── reserve đủ tiền ──► RESERVED ── capture ──► CAPTURED  (terminal)
 *     │  └── reserve thiếu tiền ──► REJECTED                (terminal)
 *     │                              │ release
 *     └── release (tombstone) ─────► RELEASED                (terminal)
 * </pre>
 */
public enum CreditReservationStatus {
    RESERVED,
    REJECTED,
    CAPTURED,
    RELEASED
}
