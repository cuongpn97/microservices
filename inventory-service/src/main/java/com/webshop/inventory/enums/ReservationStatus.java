package com.webshop.inventory.enums;

/**
 * Trạng thái reservation và các chuyển trạng thái hợp lệ:
 * <pre>
 *            reserve (đủ hàng)            commit
 *   (none) ───────────────────► RESERVED ─────────► COMMITTED   (terminal)
 *     │  │                         │
 *     │  │ reserve (thiếu hàng)    │ release
 *     │  └──────────────► REJECTED │            (terminal, không giữ gì)
 *     │                            ▼
 *     └── release (tombstone) ──► RELEASED  (terminal)
 * </pre>
 * "Tombstone": release đến TRƯỚC reserve (message trễ / Saga timeout) vẫn tạo bản ghi RELEASED,
 * nhờ đó reserve đến muộn sẽ bị từ chối thay vì giữ hàng cho một đơn đã bị hủy.
 */
public enum ReservationStatus {
    RESERVED,
    REJECTED,
    COMMITTED,
    RELEASED;

    public boolean holdsStock() {
        return this == RESERVED;
    }
}
