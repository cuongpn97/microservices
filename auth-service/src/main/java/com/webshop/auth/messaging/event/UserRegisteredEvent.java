package com.webshop.auth.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload event UserRegistered v1, gửi qua outbox → RabbitMQ (Payment Service nghe để tạo ví).
 * Cố ý KHÔNG chứa email/mật khẩu: consumer chỉ cần userId (data minimization).
 * Đây là contract giữa các service: đổi tên/bỏ field là breaking change.
 */
public record UserRegisteredEvent(UUID userId, Instant registeredAt) {
}
