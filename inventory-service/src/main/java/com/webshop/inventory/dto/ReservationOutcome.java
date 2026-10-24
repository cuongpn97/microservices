package com.webshop.inventory.dto;

import com.webshop.inventory.enums.ReservationStatus;

import java.util.UUID;

/**
 * Kết quả xử lý một command reserve/commit/release (InventoryService → InventoryCommandListener).
 * {@code applied=false} nghĩa là command trùng hoặc không hợp lệ ở trạng thái hiện tại nên KHÔNG có thay đổi
 * tồn kho — nhưng reply vẫn được gửi để bên gọi (Saga) luôn nhận được câu trả lời.
 * <p>
 * Nằm ở dto/ (không phải dto/response/) vì đây là dữ liệu giữa các layer bên trong service, không trả qua HTTP.
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
