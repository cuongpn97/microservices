package com.webshop.payment.dto;

import java.util.UUID;

/**
 * Kết quả của một lệnh tài chính (WalletService → PaymentCommandListener, rồi thành reply gửi Saga).
 * {@code applied=false}: lệnh trùng hoặc không hợp lệ ở trạng thái hiện tại, KHÔNG có thay đổi số dư nào — nhưng vẫn trả reply để Saga luôn nhận được câu trả lời xác định.
 * <p>
 * status là String (không phải enum) vì giá trị này đi thẳng vào payload reply và có thể null.
 */
public record CreditOutcome(String replyType, UUID reservationId, String status, String reason, long amountVnd,
                            boolean applied) {

    public static final String RESERVED = "CreditReserved";
    public static final String RESERVATION_REJECTED = "CreditReservationRejected";
    public static final String CAPTURED = "CreditCaptured";
    public static final String CAPTURE_REJECTED = "CreditCaptureRejected";
    public static final String RELEASED = "CreditReleased";
    public static final String RELEASE_REJECTED = "CreditReleaseRejected";
}
