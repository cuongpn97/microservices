package com.webshop.inventory.application;

import com.webshop.inventory.domain.ReservationStatus;

import java.util.UUID;

/**
 * Kết quả xử lý một command. {@code applied=false} nghĩa là command trùng hoặc không hợp lệ ở trạng thái hiện tại
 * nên KHÔNG có thay đổi tồn kho — nhưng reply vẫn được gửi để bên gọi (Saga) luôn nhận được câu trả lời.
 */
public record ReservationOutcome(String replyType, UUID reservationId, ReservationStatus status, String reason,
                                 boolean applied) {

    public static final String RESERVED = "InventoryReserved";
    public static final String RESERVATION_REJECTED = "InventoryReservationRejected";
    public static final String COMMITTED = "InventoryCommitted";
    public static final String COMMIT_REJECTED = "InventoryCommitRejected";
    public static final String RELEASED = "InventoryReleased";
    public static final String RELEASE_REJECTED = "InventoryReleaseRejected";
}
