package com.webshop.order.entity;

import com.webshop.order.enums.SagaState;

import java.time.Instant;

/**
 * Một dòng nhật ký chuyển trạng thái Saga (bảng saga_transition, append-only).
 * Record thuần (không phải JPA entity) — đọc/ghi qua SagaTransitionRepository bằng JDBC.
 *
 * @param fromState null ở dòng đầu tiên (Saga vừa bắt đầu).
 * @param trigger   loại reply gây ra chuyển trạng thái, "Timeout", hoặc "CheckoutRequested".
 */
public record SagaTransition(
        SagaState fromState,
        SagaState toState,
        String trigger,
        String detail,
        Instant createdAt) {
}
