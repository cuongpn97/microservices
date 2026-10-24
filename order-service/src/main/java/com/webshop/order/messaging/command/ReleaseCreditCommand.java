package com.webshop.order.messaging.command;

import java.util.UUID;

/**
 * Command "ReleaseCredit" v1 (bù trừ): trả tiền đang giữ. reason = failureReason của Saga.
 * userId cần để Payment tạo tombstone khi release đến trước reserve.
 */
public record ReleaseCreditCommand(UUID reservationId, UUID userId, String reason) {
}
