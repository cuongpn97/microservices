package com.webshop.payment.messaging.command;

import java.util.UUID;

/**
 * Payload chung của command "CaptureCredit" và "ReleaseCredit" v1.
 * userId và reason chỉ dùng cho Release (userId cần để tạo tombstone khi release đến trước reserve).
 */
public record CreditRefCommand(UUID reservationId, UUID userId, String reason) {
}
