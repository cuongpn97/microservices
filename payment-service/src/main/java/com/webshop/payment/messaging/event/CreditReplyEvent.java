package com.webshop.payment.messaging.event;

import java.util.UUID;

/**
 * Payload reply gửi lại Saga (routing key payment.reply). Loại reply (CreditReserved, CreditCaptured...)
 * nằm ở eventType của envelope, không nằm trong payload.
 */
public record CreditReplyEvent(UUID reservationId, String status, String reason, long amountVnd) {
}
