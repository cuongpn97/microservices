package com.webshop.payment.messaging.event;

import java.util.UUID;

/**
 * Payload event UserRegistered v1 do Auth Service phát. Auth còn gửi registeredAt nhưng Payment không cần
 * → không khai báo; Jackson bỏ qua field thừa. Mỗi service chỉ giữ phần contract mà nó thực sự dùng.
 */
public record UserRegisteredEvent(UUID userId) {
}
